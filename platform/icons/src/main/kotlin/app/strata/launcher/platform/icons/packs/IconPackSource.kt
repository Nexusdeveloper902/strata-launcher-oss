package app.strata.launcher.platform.icons.packs

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** An installed pack before parsing. */
data class PackCandidate(val packageName: String, val label: String, val versionCode: Long)

/**
 * Reads icon packs (ANDROID_PLATFORM §11.1). Only resources are read: no code is loaded, no package context with
 * `CONTEXT_INCLUDE_CODE` is created. Every call runs off the main thread and never throws.
 */
interface IconPackSource {
    /** Installed packs, or null when the query itself failed (the caller keeps what it knew). */
    fun discover(): List<PackCandidate>?

    /** The pack's `appfilter.xml` (`res/xml/`, then `res/raw/`, then `assets/`), parsed with all limits. */
    fun appFilter(pack: String): AppFilterResult

    /** The pack's optional `res/xml/drawable.xml` (the manual picker list), or null when absent or refused. */
    fun drawableList(pack: String): AppFilter?

    /** One drawable by entry name, or null when absent, too large or failing. */
    fun drawable(
        pack: String,
        name: String,
        densityDpi: Int,
    ): Drawable?
}

/**
 * The resource reads [PackXmlReader] needs from one pack, so the lookup order and the bounds are testable without a
 * real installed package. Implemented over `Resources` by [ResourcesPackResources].
 */
interface PackResources {
    /** Resource id of [name] of [type] (`xml`, `raw`), or 0. */
    fun id(
        name: String,
        type: String,
    ): Int

    /** The resource's file bytes (compiled for `xml`). */
    fun open(id: Int): InputStream

    /** A parser over a compiled `xml` resource. */
    fun xml(id: Int): XmlPullParser

    /** Closes a parser from [xml]. */
    fun close(parser: XmlPullParser)

    /** `assets/<path>`; throws `IOException` when absent. */
    fun asset(path: String): InputStream

    fun entryName(id: Int): String?
}

/** [PackResources] over a pack's `Resources`. */
class ResourcesPackResources(
    private val res: Resources,
    private val pack: String,
) : PackResources {
    @SuppressLint("DiscouragedApi") // A pack's resources are only reachable by name (ADW convention).
    override fun id(
        name: String,
        type: String,
    ): Int = res.getIdentifier(name, type, pack)

    override fun open(id: Int): InputStream = res.openRawResource(id)

    override fun xml(id: Int): XmlPullParser = res.getXml(id)

    override fun close(parser: XmlPullParser) {
        (parser as? android.content.res.XmlResourceParser)?.close()
    }

    override fun asset(path: String): InputStream = res.assets.open(path)

    override fun entryName(id: Int): String? = res.getResourceEntryName(id)
}

/** Lookup order and bounds of a pack XML file (`appfilter`, `drawable`); never throws for bad input. */
object PackXmlReader {
    /** Null when the pack has no such file at all. */
    fun read(
        res: PackResources,
        name: String,
        newParser: () -> XmlPullParser = Xml::newPullParser,
    ): AppFilterResult? {
        try {
            val xmlId = res.id(name, "xml")
            if (xmlId != 0) {
                // Compiled XML: bound the compiled size, then walk the resource parser (no DTD exists in it).
                val size = res.open(xmlId).use(::boundedLength)
                if (size > AppFilterParser.MAX_BYTES) return AppFilterResult.Rejected(PackProblem.TOO_LARGE)
                val parser = res.xml(xmlId)
                return try {
                    AppFilterParser.parse(parser, entryName = res::entryName)
                } finally {
                    res.close(parser)
                }
            }
            val rawId = res.id(name, "raw")
            val bytes =
                if (rawId != 0) {
                    res.open(rawId).use(::boundedRead)
                } else {
                    try {
                        res.asset("$name.xml")
                    } catch (e: IOException) {
                        return null
                    }.use(::boundedRead)
                } ?: return AppFilterResult.Rejected(PackProblem.TOO_LARGE)
            return AppFilterParser.parseText(bytes, newParser)
        } catch (e: IOException) {
            return AppFilterResult.Rejected(PackProblem.MALFORMED)
        } catch (e: RuntimeException) {
            return AppFilterResult.Rejected(PackProblem.MALFORMED)
        } catch (e: OutOfMemoryError) {
            return AppFilterResult.Rejected(PackProblem.TOO_LARGE)
        }
    }

    /** Reads at most [AppFilterParser.MAX_BYTES]; null when the stream is longer. */
    fun boundedRead(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(BUFFER)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > AppFilterParser.MAX_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Counts bytes up to just past [AppFilterParser.MAX_BYTES]. */
    fun boundedLength(input: InputStream): Int {
        val buf = ByteArray(BUFFER)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) return total
            total += n
            if (total > AppFilterParser.MAX_BYTES) return total
        }
    }

    private const val BUFFER = 16 * 1024
}

/** [IconPackSource] over `PackageManager` (visible through the manifest's `<queries>` THEMES intent). */
class PackageManagerIconPackSource(context: Context) : IconPackSource {
    private val appContext = context.applicationContext
    private val pm: PackageManager get() = appContext.packageManager
    private val resources = HashMap<String, Resources>()

    override fun discover(): List<PackCandidate>? {
        val infos =
            try {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(Intent(ACTION_ADW_THEMES), 0)
            } catch (e: RuntimeException) {
                return null
            }
        val own = appContext.packageName
        return infos
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != own }
            .take(MAX_PACKS)
            .mapNotNull { app ->
                try {
                    val label =
                        app
                            .loadLabel(pm)
                            .toString()
                            .take(MAX_LABEL)
                            .ifBlank { app.packageName }
                    PackCandidate(app.packageName, label, versionCode(app.packageName))
                } catch (e: RuntimeException) {
                    null
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
            }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(pack: String): Long {
        val info = pm.getPackageInfo(pack, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }

    private fun res(pack: String): Resources? =
        synchronized(resources) {
            resources[pack] ?: try {
                pm.getResourcesForApplication(pack).also { resources[pack] = it }
            } catch (e: PackageManager.NameNotFoundException) {
                null
            } catch (e: RuntimeException) {
                null
            }
        }

    /** Drops cached `Resources` (a pack was updated or removed). */
    fun forget(pack: String) {
        synchronized(resources) { resources.remove(pack) }
    }

    override fun appFilter(pack: String): AppFilterResult =
        res(pack)?.let { PackXmlReader.read(ResourcesPackResources(it, pack), "appfilter") }
            ?: AppFilterResult.Rejected(PackProblem.MISSING)

    override fun drawableList(pack: String): AppFilter? =
        res(
            pack,
        )?.let { (PackXmlReader.read(ResourcesPackResources(it, pack), "drawable") as? AppFilterResult.Ok)?.filter }

    @SuppressLint("DiscouragedApi")
    override fun drawable(
        pack: String,
        name: String,
        densityDpi: Int,
    ): Drawable? {
        val res = res(pack) ?: return null
        return try {
            val id = res.getIdentifier(name, "drawable", pack)
            if (id == 0 || tooLarge(res, id, densityDpi)) null else res.getDrawableForDensity(id, densityDpi, null)
        } catch (e: Resources.NotFoundException) {
            null
        } catch (e: RuntimeException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /**
     * Bitmap drawables (`.png`, `.webp`, `.jpg`) larger than [MAX_BITMAP_SIDE] on a side are skipped before decoding:
     * a hostile pack could ship a 20 000 px image. Vector and XML drawables are not bitmaps and pass.
     */
    private fun tooLarge(
        res: Resources,
        id: Int,
        densityDpi: Int,
    ): Boolean {
        val value = TypedValue()
        res.getValueForDensity(id, densityDpi, value, true)
        val file = value.string?.toString()?.lowercase() ?: return false
        if (BITMAP_EXTENSIONS.none { file.endsWith(it) }) return false
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        res.openRawResource(id, value).use { BitmapFactory.decodeStream(it, null, opts) }
        return opts.outWidth > MAX_BITMAP_SIDE || opts.outHeight > MAX_BITMAP_SIDE
    }

    companion object {
        /** The ecosystem's icon-pack action (ADW convention, SEC-006; declared in the manifest's `<queries>`). */
        const val ACTION_ADW_THEMES = "org.adw.ActivityStarter.THEMES"
        private const val MAX_PACKS = 64
        private const val MAX_LABEL = 64

        /** Icons are drawn at ≤ 512 px; anything over this is refused before decoding (Proposed). */
        const val MAX_BITMAP_SIDE = 1024
        private val BITMAP_EXTENSIONS = listOf(".png", ".webp", ".jpg", ".jpeg")
    }
}
