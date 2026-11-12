package app.strata.launcher.platform.icons

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.IconCoverageEntry
import app.strata.launcher.core.model.IconImage
import app.strata.launcher.core.model.IconOverride
import app.strata.launcher.core.model.IconPacks
import app.strata.launcher.core.model.IconRequest
import app.strata.launcher.core.model.IconResult
import app.strata.launcher.core.model.IconSource
import app.strata.launcher.core.model.IconStyle
import app.strata.launcher.core.model.PackDrawables
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.icons.IconProvider
import app.strata.launcher.platform.icons.packs.IconPackRegistry
import app.strata.launcher.platform.icons.packs.IconPackSource
import app.strata.launcher.platform.icons.packs.PackageManagerIconPackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The icon pipeline of ARCHITECTURE §9. Source by THEME_SYSTEM §4.6 precedence ([IconSourceResolver]): per-app
 * override, the theme's pack mapping, then (tinted only) Strata glyph, platform monochrome (API 33+), desaturated;
 * untinted apps show their system icon in the theme's shape. Rendered by [IconRenderer] at the exact pixel size,
 * badged afterwards for other profiles. L1 = byte-sized [LruCache] of at most 1/8 of the memory class; L2 = files in
 * `cacheDir/icons/` (not backed up), LRU-capped at [DISK_CAP_BYTES]. The cache key covers every request field, the
 * package's `lastUpdateTime`, the packs' version and parse state, the calendar day for calendar icons and the
 * glyph bundle version (§9.1).
 */
class AndroidIconProvider(
    context: Context,
    private val renderDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val logger: Logger,
    maxMemoryBytes: Int = defaultMemoryBudget(context),
    /**
     * L2 directory. Resolved lazily, on the I/O dispatcher: `getCacheDir()` touches the disk, and the provider is
     * built on main from `HomeActivity.onCreate` (StrictMode disk-read violation on API 36, CI run 41576277117).
     */
    diskDir: (() -> File)? = null,
    packSource: IconPackSource? = null,
    private val apiLevel: Int = Build.VERSION.SDK_INT,
    private val dayOfMonth: () -> Int = { Calendar.getInstance().get(Calendar.DAY_OF_MONTH) },
    /** The Strata glyph set (FR-THEME-35); default: the bundled index and drawables. */
    glyphs: GlyphLookup? = null,
) : IconProvider {
    private val appContext = context.applicationContext
    private val diskDir: File by lazy { diskDir?.invoke() ?: File(appContext.cacheDir, "icons") }
    private val disk by lazy { DiskLru(this.diskDir, DISK_CAP_BYTES) }
    private val launcherApps by lazy { appContext.getSystemService(LauncherApps::class.java) }
    private val userManager by lazy { appContext.getSystemService(UserManager::class.java) }
    private val source: IconPackSource by lazy { packSource ?: PackageManagerIconPackSource(appContext) }
    private val glyphs: GlyphLookup =
        glyphs ?: BundledGlyphs({
            appContext.resources
                .openRawResource(
                    BundledGlyphs.INDEX_RES,
                ).use { it.readBytes().toString(Charsets.UTF_8) }
        })

    /** Pack discovery and parsing: serial, off main (T-SEC-06: no ANR). */
    private val packWorker: CoroutineDispatcher by lazy {
        // `Unconfined` (tests) cannot be limited; it is serial on the caller's thread anyway.
        try {
            ioDispatcher.limitedParallelism(1)
        } catch (e: UnsupportedOperationException) {
            ioDispatcher
        }
    }
    private val registry by lazy { IconPackRegistry(source, packWorker, scope, logger) }
    private val started = AtomicBoolean(false)

    private val memory =
        object : LruCache<String, IconImage>(maxMemoryBytes.coerceAtLeast(1)) {
            override fun sizeOf(
                key: String,
                value: IconImage,
            ): Int = (value.native as? Bitmap)?.allocationByteCount ?: (value.width * value.height * 4)
        }

    override val iconPacks: StateFlow<IconPacks> get() = registry.packs

    override val revision: StateFlow<Long> get() = registry.revision

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        registry.refresh()
        try {
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addAction(Intent.ACTION_PACKAGE_CHANGED)
                    addDataScheme("package")
                }
            // System broadcasts only: not exported (API 33+ requires the flag; it still receives system broadcasts).
            ContextCompat.registerReceiver(appContext, PackageReceiver(), filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (e: RuntimeException) {
            logger.w(TAG, "package receiver not registered", e)
        }
    }

    private inner class PackageReceiver : BroadcastReceiver() {
        override fun onReceive(
            context: Context?,
            intent: Intent?,
        ) {
            // An update sends REMOVED (replacing) then ADDED/REPLACED: refreshing on the first would show the pack as
            // missing for a moment (a false notice).
            if (isTransientRemoval(
                    intent?.action,
                    intent?.getBooleanExtra(Intent.EXTRA_REPLACING, false) == true,
                )
            ) {
                return
            }
            refreshPacks()
        }
    }

    override fun refreshPacks() = registry.refresh()

    /** Tests: discovery and parsing on the caller's thread. */
    fun refreshPacksNow() = registry.refreshNow()

    override fun cached(req: IconRequest): IconImage? = if (req.ephemeral) null else memory.get(cacheKey(req))

    override suspend fun icon(req: IconRequest): IconResult {
        if (req.ephemeral) return ephemeralIcon(req)
        val key = cacheKey(req)
        memory.get(key)?.let { return IconResult.Image(it) }
        return try {
            val disk = withContext(ioDispatcher) { readDisk(key) }
            val image = disk ?: withContext(renderDispatcher) { render(req) }
            if (image == null) {
                IconResult.Monogram
            } else {
                // The pack state read while rendering must be the one the key was made from: a pack parsed (or
                // removed) in between would otherwise leave this bitmap cached under the old key.
                if (disk != null || cacheKey(req) == key) {
                    memory.put(key, image)
                    if (disk == null) scope.launch(ioDispatcher) { writeDisk(key, image.native as Bitmap) }
                }
                IconResult.Image(image)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "icon failed for ${req.key.component}", e)
            IconResult.Monogram
        } catch (e: OutOfMemoryError) {
            logger.w(TAG, "icon too large for ${req.key.component}", e)
            IconResult.Monogram
        }
    }

    /**
     * FR-APPS-10, P-5: a private-space icon is rendered for this request only. It never enters L1 or L2 (nothing about
     * the space reaches the disk, and nothing is left in memory once its rows are gone), and failures are not logged
     * with the component.
     */
    private suspend fun ephemeralIcon(req: IconRequest): IconResult =
        try {
            withContext(renderDispatcher) { render(req) }?.let { IconResult.Image(it) } ?: IconResult.Monogram
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            IconResult.Monogram
        } catch (e: OutOfMemoryError) {
            IconResult.Monogram
        }

    override fun trim(critical: Boolean) {
        if (critical) memory.evictAll() else memory.trimToSize(memory.maxSize() / 2)
    }

    override suspend fun packDrawables(
        pack: String,
        component: String?,
    ): PackDrawables? =
        withContext(packWorker) {
            val filter = registry.mapping(pack) ?: return@withContext null
            val names = (source.drawableList(pack)?.drawables ?: filter.drawables).take(MAX_CHOICES)
            val suggested = component?.let { filter.drawableFor(it, dayOfMonth()) }
            PackDrawables(pack, names, suggested)
        }

    override suspend fun packIcon(
        pack: String,
        drawable: String,
        sizePx: Int,
        densityDpi: Int,
    ): IconResult {
        val key = digest(listOf("pack", registry.keyPart(pack), drawable, sizePx, densityDpi).joinToString("|"))
        memory.get(key)?.let { return IconResult.Image(it) }
        return try {
            withContext(renderDispatcher) {
                if (!registry.isUsable(pack)) return@withContext IconResult.Monogram
                val d = source.drawable(pack, drawable, densityDpi) ?: return@withContext IconResult.Monogram
                val bitmap = IconRenderer.asSupplied(d, sizePx.coerceIn(1, MAX_SIZE_PX))
                IconImage(bitmap, bitmap.width, bitmap.height).also { memory.put(key, it) }.let { IconResult.Image(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            IconResult.Monogram
        } catch (e: OutOfMemoryError) {
            IconResult.Monogram
        }
    }

    private fun render(req: IconRequest): IconImage? {
        val component = ComponentName.unflattenFromString(req.key.component) ?: return null
        val user = userManager?.getUserForSerialNumber(req.key.userSerial) ?: return null
        val size = req.sizePx.coerceIn(1, MAX_SIZE_PX)
        val s = sources(component, req.key.component, user, req.style, req.override, req.densityDpi)
        var bitmap = IconRenderer.render(s.kind, size, req.style, s.icon, s.layer, s.supplied) ?: return null
        if (user != Process.myUserHandle()) bitmap = badged(bitmap, user, size)
        return IconImage(bitmap, bitmap.width, bitmap.height)
    }

    /** What [render] draws for one app: the [IconSourceResolver] step and the drawables it needs. */
    private class Sources(
        val kind: IconSource,
        val icon: Drawable?,
        val layer: Drawable?,
        val supplied: Drawable?,
    )

    /**
     * THEME_SYSTEM §4.6 inputs, loaded in precedence order (override, pack, glyph, monochrome, system icon) so the
     * coverage tool and the renderer take the same decision from the same reads (ARCHITECTURE §9.1).
     */
    private fun sources(
        component: ComponentName,
        /** The key's component string, as the pack mapping is looked up (unchanged from M5). */
        componentKey: String,
        user: UserHandle,
        style: IconStyle,
        overrideRef: IconOverride?,
        densityDpi: Int,
    ): Sources {
        val override = overrideRef?.takeIf { registry.isUsable(it.packPackage) }
        val overrideDrawable = override?.let { source.drawable(it.packPackage, it.drawable, densityDpi) }
        val packDrawable =
            if (overrideDrawable != null) {
                null
            } else {
                style.packPackage?.let { pack ->
                    registry
                        .mapping(pack)
                        ?.drawableFor(componentKey, dayOfMonth())
                        ?.let { source.drawable(pack, it, densityDpi) }
                }
            }
        val supplied = overrideDrawable ?: packDrawable
        val glyphRes =
            if (supplied == null && IconSourceResolver.isTinted(style)) glyphs.glyphRes(component.packageName) else null
        val glyph = glyphRes?.let { glyphDrawable(it) }
        val icon: Drawable? =
            if (supplied != null || glyph != null) null else systemIcon(component, user, densityDpi)
        val monochrome =
            if (apiLevel >= IconSourceResolver.MONOCHROME_API &&
                icon is AdaptiveIconDrawable
            ) {
                monochromeOf(icon)
            } else {
                null
            }
        val kind =
            IconSourceResolver.resolve(
                style = style,
                overrideAvailable = overrideDrawable != null,
                packMapped = packDrawable != null,
                hasGlyph = glyph != null,
                hasMonochromeLayer = monochrome != null,
                apiLevel = apiLevel,
            )
        return Sources(kind, icon, if (kind == IconSource.GLYPH) glyph else monochrome, supplied)
    }

    private fun glyphDrawable(res: Int): Drawable? =
        try {
            ContextCompat.getDrawable(appContext, res)
        } catch (e: RuntimeException) {
            logger.w(TAG, "glyph drawable failed", e)
            null
        }

    /**
     * FR-SET-07: the icon source of each app under [style], decided like [render] without drawing. Private-space apps
     * are skipped (P-5); failures of one app give no row rather than a wrong one. Off main.
     */
    override suspend fun coverage(
        apps: List<VisibleApp>,
        style: IconStyle,
    ): List<IconCoverageEntry> =
        withContext(ioDispatcher) {
            val density = appContext.resources.displayMetrics.densityDpi
            apps.mapNotNull { app ->
                if (app.profileKind == ProfileKind.PRIVATE) return@mapNotNull null
                try {
                    val component = ComponentName.unflattenFromString(app.key.component) ?: return@mapNotNull null
                    val user = userManager?.getUserForSerialNumber(app.key.userSerial) ?: return@mapNotNull null
                    val kind = sources(component, app.key.component, user, style, app.iconOverride, density).kind
                    IconCoverageEntry(
                        app.key,
                        app.packageName,
                        app.displayLabel,
                        app.profileKind,
                        kind,
                        app.systemLabel,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(TAG, "coverage failed for one app", e)
                    null
                } catch (e: OutOfMemoryError) {
                    null
                }
            }
        }

    private fun systemIcon(
        component: ComponentName,
        user: UserHandle,
        densityDpi: Int,
    ): Drawable? {
        val apps = launcherApps ?: return null
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
        val info = apps.resolveActivity(intent, user) ?: return null
        return info.getIcon(densityDpi)
    }

    private fun monochromeOf(icon: AdaptiveIconDrawable): Drawable? =
        if (Build.VERSION.SDK_INT >= IconSourceResolver.MONOCHROME_API) icon.monochrome else null

    /** Work and other profiles: the system badge on the final icon (VISUAL_SPEC §7). */
    private fun badged(
        bitmap: Bitmap,
        user: UserHandle,
        size: Int,
    ): Bitmap {
        val drawable = BitmapDrawable(appContext.resources, bitmap)
        val badged = appContext.packageManager.getUserBadgedIcon(drawable, user)
        return if (badged === drawable) bitmap else badged.toBitmap(size, size, Bitmap.Config.ARGB_8888)
    }

    private fun readDisk(key: String): IconImage? {
        val file = File(diskDir, "$key.$EXT")
        if (!file.isFile) return null
        val bitmap = BitmapFactory.decodeFile(file.path)
        if (bitmap == null) {
            file.delete()
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        return IconImage(bitmap, bitmap.width, bitmap.height)
    }

    private fun writeDisk(
        key: String,
        bitmap: Bitmap,
    ) {
        runCatching {
            diskDir.mkdirs()
            // A unique temp file: concurrent writes of the same key must not share (and corrupt) one.
            val tmp = File.createTempFile("w-$key-", ".tmp", diskDir)
            val target = File(diskDir, "$key.$EXT")
            try {
                tmp.outputStream().use { bitmap.compress(format(), QUALITY, it) }
                if (!tmp.renameTo(target)) tmp.delete()
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
            disk.onWritten(target.length())
        }.onFailure { logger.w(TAG, "icon disk write failed", it) }
    }

    @Suppress("DEPRECATION")
    private fun format(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.R
        ) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            Bitmap.CompressFormat.WEBP
        }

    /**
     * Cache key (ARCHITECTURE §9): every request field, plus the pack state (version, parsed or not, usable) of the
     * override's and the theme's pack, plus the day of month when the theme's pack draws this app as a calendar.
     * Cheap (a few map reads and one SHA-1): [cached] runs on main for every row a frame composes.
     */
    fun cacheKey(req: IconRequest): String {
        val s = req.style
        val pack = s.packPackage
        val calendarDay =
            if (pack != null && registry.mapping(pack)?.isCalendar(req.key.component) == true) dayOfMonth() else 0
        val raw =
            buildString {
                append(baseKey(req))
                append('|')
                    .append(s.shape)
                    .append('|')
                    .append(s.cornerRadiusPercent)
                    .append('|')
                    .append(s.legacyPlate)
                append('|')
                    .append(s.plateColor)
                    .append('|')
                    .append(s.tint)
                    .append('|')
                    .append(s.tintColor)
                append('|').append(s.packFallbackMonochrome).append('|').append(pack?.let(registry::keyPart))
                append('|').append(req.override?.let { registry.keyPart(it.packPackage) + ":" + it.drawable })
                append('|')
                    .append(calendarDay)
                    .append('|')
                    .append(apiLevel)
                    .append('|')
                    .append(glyphs.version)
            }
        return digest(raw)
    }

    companion object {
        private const val TAG = "Icons"
        private const val EXT = "webp"
        private const val QUALITY = 100
        private const val MAX_SIZE_PX = 512
        private const val MAX_CHOICES = 20_000

        /** ARCHITECTURE §9: L2 is capped at 32 MB. */
        const val DISK_CAP_BYTES: Long = 32L * 1024 * 1024

        /** `PACKAGE_REMOVED` that is part of an update (`EXTRA_REPLACING`): the pack comes straight back. */
        fun isTransientRemoval(
            action: String?,
            replacing: Boolean,
        ): Boolean = action == Intent.ACTION_PACKAGE_REMOVED && replacing

        /** The request half of the key: format version, component, user, update time, size, density. */
        fun baseKey(req: IconRequest): String =
            listOf("v2", req.key.component, req.key.userSerial, req.lastUpdateTime, req.sizePx, req.densityDpi)
                .joinToString("|")

        private fun digest(raw: String): String = hex(MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()))

        /**
         * Lower-case hex, the same text as `"%02x".format` per byte (so existing L2 file names stay valid), without
         * a `Formatter` per byte: [cached] runs on main for every row a list or search frame composes (C-6).
         */
        internal fun hex(bytes: ByteArray): String {
            val out = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val v = bytes[i].toInt() and 0xff
                out[i * 2] = HEX[v ushr 4]
                out[i * 2 + 1] = HEX[v and 0x0f]
            }
            return String(out)
        }

        private val HEX = "0123456789abcdef".toCharArray()

        /** NFR-PERF-04: the L1 cache is bounded by 1/8 of the memory class. */
        fun defaultMemoryBudget(context: Context): Int {
            val am = context.getSystemService(ActivityManager::class.java)
            val classMb = am?.memoryClass ?: DEFAULT_MEMORY_CLASS_MB
            return classMb * 1024 * 1024 / 8
        }

        private const val DEFAULT_MEMORY_CLASS_MB = 64
    }
}
