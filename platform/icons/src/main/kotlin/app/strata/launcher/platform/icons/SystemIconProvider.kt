package app.strata.launcher.platform.icons

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.IconImage
import app.strata.launcher.core.model.IconRequest
import app.strata.launcher.core.model.IconResult
import app.strata.launcher.core.platformapi.icons.IconProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * System icons (ARCHITECTURE §9, M3 scope): `LauncherActivityInfo.getIcon(density)` (badged for other profiles),
 * drawn at the exact pixel size. L1 = byte-sized [LruCache] of at most 1/8 of the memory class; L2 = files in
 * `cacheDir/icons/` (not backed up), LRU-capped at [DISK_CAP_BYTES]. The cache key includes the package's
 * `lastUpdateTime`, so an update invalidates the entry (T-LIFE-06). Packs, shapes and tints arrive in M5.
 */
class SystemIconProvider(
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
) : IconProvider {
    private val appContext = context.applicationContext
    private val diskDir: File by lazy { diskDir?.invoke() ?: File(appContext.cacheDir, "icons") }
    private val launcherApps by lazy { appContext.getSystemService(LauncherApps::class.java) }
    private val userManager by lazy { appContext.getSystemService(UserManager::class.java) }

    private val memory =
        object : LruCache<String, IconImage>(maxMemoryBytes.coerceAtLeast(1)) {
            override fun sizeOf(
                key: String,
                value: IconImage,
            ): Int = (value.native as? Bitmap)?.allocationByteCount ?: (value.width * value.height * 4)
        }

    override fun cached(req: IconRequest): IconImage? = memory.get(cacheKey(req))

    override suspend fun icon(req: IconRequest): IconResult {
        val key = cacheKey(req)
        memory.get(key)?.let { return IconResult.Image(it) }
        return try {
            val image = withContext(ioDispatcher) { readDisk(key) } ?: withContext(renderDispatcher) { render(req) }
            if (image == null) {
                IconResult.Monogram
            } else {
                memory.put(key, image)
                IconResult.Image(image)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "icon failed for ${req.key.component}", e)
            IconResult.Monogram
        }
    }

    override fun trim(critical: Boolean) {
        if (critical) memory.evictAll() else memory.trimToSize(memory.maxSize() / 2)
    }

    private fun render(req: IconRequest): IconImage? {
        val component = ComponentName.unflattenFromString(req.key.component) ?: return null
        val apps = launcherApps ?: return null
        val user = userManager?.getUserForSerialNumber(req.key.userSerial) ?: return null
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
        val info = apps.resolveActivity(intent, user) ?: return null
        val drawable =
            if (user == Process.myUserHandle()) info.getIcon(req.densityDpi) else info.getBadgedIcon(req.densityDpi)
        val size = req.sizePx.coerceIn(1, MAX_SIZE_PX)
        val bitmap = drawable.toBitmap(size, size, Bitmap.Config.ARGB_8888)
        val image = IconImage(bitmap, bitmap.width, bitmap.height)
        scope.launch(ioDispatcher) { writeDisk(cacheKey(req), bitmap) }
        return image
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
            try {
                tmp.outputStream().use { bitmap.compress(format(), QUALITY, it) }
                if (!tmp.renameTo(File(diskDir, "$key.$EXT"))) tmp.delete()
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
            trimDisk()
        }.onFailure { logger.w(TAG, "icon disk write failed", it) }
    }

    /** LRU by last access (mtime): delete the oldest files until the directory fits the cap. */
    private fun trimDisk() {
        val files = diskDir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_CAP_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_CAP_BYTES) break
            total -= f.length()
            f.delete()
        }
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

    companion object {
        private const val TAG = "Icons"
        private const val EXT = "webp"
        private const val QUALITY = 100
        private const val MAX_SIZE_PX = 512

        /** ARCHITECTURE §9: L2 is capped at 32 MB. */
        const val DISK_CAP_BYTES: Long = 32L * 1024 * 1024

        /** Cache key: every field of the request plus a format version (ARCHITECTURE §9). */
        fun cacheKey(req: IconRequest): String {
            val raw =
                listOf(
                    "v1",
                    req.key.component,
                    req.key.userSerial,
                    req.lastUpdateTime,
                    req.sizePx,
                    req.densityDpi,
                ).joinToString("|")
            val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

        /** NFR-PERF-04: the L1 cache is bounded by 1/8 of the memory class. */
        fun defaultMemoryBudget(context: Context): Int {
            val am = context.getSystemService(ActivityManager::class.java)
            val classMb = am?.memoryClass ?: DEFAULT_MEMORY_CLASS_MB
            return classMb * 1024 * 1024 / 8
        }

        private const val DEFAULT_MEMORY_CLASS_MB = 64
    }
}
