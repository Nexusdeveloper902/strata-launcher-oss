package app.strata.launcher.platform.icons.packs

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Xml
import java.io.IOException
import java.io.InputStream

/** An installed pack before parsing. */
data class PackCandidate(val packageName: String, val label: String, val versionCode: Long)

/**
 * Reads icon packs (ANDROID_PLATFORM §11.1). Only resources are read: no code is loaded, no package context with
 * `CONTEXT_INCLUDE_CODE` is created. Every call runs off the main thread and never throws.
 */
interface IconPackSource {
    fun discover(): List<PackCandidate>

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

/** [IconPackSource] over `PackageManager` (visible through the manifest's `<queries>` THEMES intent). */
class PackageManagerIconPackSource(context: Context) : IconPackSource {
    private val appContext = context.applicationContext
    private val pm: PackageManager get() = appContext.packageManager
    private val resources = HashMap<String, Resources>()

    override fun discover(): List<PackCandidate> {
        val infos =
            try {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(Intent(ACTION_ADW_THEMES), 0)
            } catch (e: RuntimeException) {
                emptyList()
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
        readXml(pack, "appfilter") ?: AppFilterResult.Rejected(PackProblem.MISSING)

    override fun drawableList(pack: String): AppFilter? = (readXml(pack, "drawable") as? AppFilterResult.Ok)?.filter

    @SuppressLint("DiscouragedApi") // A pack's resources are only reachable by name (ADW convention).
    private fun readXml(
        pack: String,
        name: String,
    ): AppFilterResult? {
        val res = res(pack) ?: return null
        try {
            val xmlId = res.getIdentifier(name, "xml", pack)
            if (xmlId != 0) {
                // Compiled XML: bound the compiled size, then walk the resource parser (no DTD exists in it).
                val size = res.openRawResource(xmlId).use(::boundedLength)
                if (size > AppFilterParser.MAX_BYTES) return AppFilterResult.Rejected(PackProblem.TOO_LARGE)
                return res.getXml(xmlId).use { AppFilterParser.parse(it) }
            }
            val rawId = res.getIdentifier(name, "raw", pack)
            val bytes =
                if (rawId != 0) {
                    res.openRawResource(rawId).use(::boundedRead)
                } else {
                    try {
                        res.assets.open("$name.xml").use(::boundedRead)
                    } catch (e: IOException) {
                        return null
                    }
                } ?: return AppFilterResult.Rejected(PackProblem.TOO_LARGE)
            return AppFilterParser.parseText(bytes, ::newParser)
        } catch (e: IOException) {
            return AppFilterResult.Rejected(PackProblem.MALFORMED)
        } catch (e: RuntimeException) {
            return AppFilterResult.Rejected(PackProblem.MALFORMED)
        } catch (e: OutOfMemoryError) {
            return AppFilterResult.Rejected(PackProblem.TOO_LARGE)
        }
    }

    private fun newParser() = Xml.newPullParser()

    @SuppressLint("DiscouragedApi")
    override fun drawable(
        pack: String,
        name: String,
        densityDpi: Int,
    ): Drawable? {
        val res = res(pack) ?: return null
        return try {
            val id = res.getIdentifier(name, "drawable", pack)
            if (id == 0) null else res.getDrawableForDensity(id, densityDpi, null)
        } catch (e: Resources.NotFoundException) {
            null
        } catch (e: RuntimeException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    companion object {
        /** The ecosystem's icon-pack action (ADW convention, SEC-006; declared in the manifest's `<queries>`). */
        const val ACTION_ADW_THEMES = "org.adw.ActivityStarter.THEMES"
        private const val MAX_PACKS = 64
        private const val MAX_LABEL = 64

        /** Reads at most [AppFilterParser.MAX_BYTES]; null when the stream is longer. */
        internal fun boundedRead(input: InputStream): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
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

        private fun boundedLength(input: InputStream): Int {
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) return total
                total += n
                if (total > AppFilterParser.MAX_BYTES) return total
            }
        }
    }
}
