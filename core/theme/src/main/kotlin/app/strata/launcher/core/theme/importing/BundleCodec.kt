package app.strata.launcher.core.theme.importing

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.json.JsonArray
import app.strata.launcher.core.json.JsonLimits
import app.strata.launcher.core.json.JsonNumber
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonParser
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.json.JsonValue
import app.strata.launcher.core.json.JsonWriter
import app.strata.launcher.core.theme.model.ImageMediaType
import java.security.MessageDigest

/** A user font carried by a bundle or stored in the font store (content-addressed). */
class ThemeFont(val sha256: String, val format: FontFormat, val bytes: ByteArray) {
    val storeName: String get() = "$sha256.${format.extension}"
}

/** A wallpaper image carried by a bundle or stored in the image store (content-addressed). */
class ThemeImage(val sha256: String, val type: ImageMediaType, val bytes: ByteArray) {
    val extension: String
        get() =
            when (type) {
                ImageMediaType.PNG -> "png"
                ImageMediaType.JPEG -> "jpg"
                ImageMediaType.WEBP -> "webp"
            }
}

/** The verified content of a `.strata-theme.zip` (THEME_SYSTEM §10.3). Fonts and image are not yet platform-checked. */
class BundleContents(
    val theme: ByteArray,
    val fonts: List<ThemeFont>,
    val image: ThemeImage?,
)

/**
 * Reader and writer of theme bundles (THEME_SYSTEM §10.3): allow-listed entry names, manifest rules (§10.3.2) and the
 * limits of §10.3.3 through [BoundedZip]. The theme document itself is validated by the caller (§8).
 */
object BundleCodec {
    const val FORMAT = "strata-theme-bundle"
    const val VERSION = 1
    const val MANIFEST = "manifest.json"
    const val THEME = "theme.json"
    const val MAX_FONTS = 4
    const val MAX_IMAGES = 1
    const val MAX_THEME_BYTES = 256 * 1024

    /** §10.3.1, exact and case-sensitive. */
    val ENTRY_NAME =
        Regex("^(manifest\\.json|theme\\.json|fonts/[0-9a-f]{64}\\.(ttf|otf)|images/[0-9a-f]{64}\\.(png|jpg|webp))$")

    val LIMITS =
        ZipLimits(
            entryCap = { name ->
                when {
                    name == MANIFEST -> JsonLimits.BUNDLE_MANIFEST.maxBytes.toLong()
                    name == THEME -> MAX_THEME_BYTES.toLong()
                    name.startsWith("fonts/") -> FontCheck.MAX_BYTES.toLong()
                    name.startsWith("images/") -> ImageCheck.MAX_BYTES.toLong()
                    else -> 0L
                }
            },
            acceptName = { ENTRY_NAME.matches(it) },
        )

    fun read(archive: ByteArray): Outcome<BundleContents, ImportError.Bundle> {
        val entries =
            when (val r = BoundedZip.read(archive, LIMITS)) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return r
            }
        return try {
            Outcome.Ok(verify(entries))
        } catch (e: BoundedZip.BundleException) {
            Outcome.Err(ImportError.Bundle(e.problem, e.message ?: e.problem.name))
        }
    }

    private fun fail(
        p: BundleProblem,
        msg: String,
    ): Nothing = throw BoundedZip.BundleException(p, msg)

    private fun verify(entries: List<ZipFileEntry>): BundleContents {
        for (e in entries) if (!ENTRY_NAME.matches(e.name)) fail(BundleProblem.UNEXPECTED_ENTRY, "entry not allowed")
        val byName = entries.associateBy { it.name }
        val manifestBytes = byName[MANIFEST]?.bytes ?: fail(BundleProblem.BAD_MANIFEST, "no manifest")
        val manifest =
            when (val r = JsonParser.parse(manifestBytes, JsonLimits.BUNDLE_MANIFEST)) {
                is Outcome.Ok -> r.value as? JsonObject ?: fail(BundleProblem.BAD_MANIFEST, "manifest not an object")
                is Outcome.Err -> fail(BundleProblem.BAD_MANIFEST, "manifest is not valid JSON")
            }
        if ((manifest["bundleFormat"] as? JsonString)?.value != FORMAT) fail(BundleProblem.BAD_MANIFEST, "bundleFormat")
        val version = manifest["bundleVersion"] as? JsonNumber
        if (version == null || !version.isIntegral) fail(BundleProblem.BAD_MANIFEST, "bundleVersion")
        if (version.toBigDecimal() > VERSION.toBigDecimal()) fail(BundleProblem.NEWER_BUNDLE, "bundleVersion > 1")
        if (version.toBigDecimal() < VERSION.toBigDecimal()) fail(BundleProblem.BAD_MANIFEST, "bundleVersion < 1")

        val themeRec = manifest["theme"] as? JsonObject ?: fail(BundleProblem.BAD_MANIFEST, "theme record")
        if ((themeRec["path"] as? JsonString)?.value != THEME) fail(BundleProblem.BAD_MANIFEST, "theme path")
        val theme = byName[THEME]?.bytes ?: fail(BundleProblem.MISSING_ENTRY, "theme.json missing")
        checkRecord(themeRec, theme)

        val listed = HashSet<String>()
        listed += MANIFEST
        listed += THEME
        val fonts = ArrayList<ThemeFont>()
        var image: ThemeImage? = null
        val files = manifest["files"] ?: JsonArray.EMPTY
        if (files !is JsonArray) fail(BundleProblem.BAD_MANIFEST, "files is not an array")
        for (f in files) {
            if (f !is JsonObject) fail(BundleProblem.BAD_MANIFEST, "file record")
            val path = (f["path"] as? JsonString)?.value ?: fail(BundleProblem.BAD_MANIFEST, "file path")
            if (!ENTRY_NAME.matches(path) || path == MANIFEST || path == THEME) {
                fail(BundleProblem.BAD_MANIFEST, "listed path not allowed")
            }
            if (!listed.add(path)) fail(BundleProblem.BAD_MANIFEST, "path listed twice")
            val bytes = byName[path]?.bytes ?: fail(BundleProblem.MISSING_ENTRY, "listed file missing")
            val sha = checkRecord(f, bytes)
            val nameHash = path.substringAfter('/').substringBefore('.')
            if (nameHash != sha) fail(BundleProblem.HASH_MISMATCH, "file name hash is not the content hash")
            val role = (f["role"] as? JsonString)?.value
            val ext = path.substringAfterLast('.')
            if (path.startsWith("fonts/")) {
                if (role != "font") fail(BundleProblem.BAD_MANIFEST, "font role")
                if (fonts.size >= MAX_FONTS) fail(BundleProblem.TOO_MANY_ENTRIES, "more than $MAX_FONTS fonts")
                val format = FontCheck.check(bytes) ?: fail(BundleProblem.FONT_INVALID, "font structure")
                fonts += ThemeFont(sha, if (ext == "otf") FontFormat.OTF else format, bytes)
            } else {
                if (role != "wallpaper") fail(BundleProblem.BAD_MANIFEST, "image role")
                if (image != null) fail(BundleProblem.TOO_MANY_ENTRIES, "more than $MAX_IMAGES image")
                val header = ImageCheck.header(bytes) ?: fail(BundleProblem.IMAGE_INVALID, "image type")
                if (!ImageCheck.withinLimits(header)) fail(BundleProblem.IMAGE_INVALID, "image over the pixel limits")
                image = ThemeImage(sha, header.type, bytes)
            }
        }
        for (name in byName.keys) if (name !in listed) fail(BundleProblem.UNLISTED_ENTRY, "entry not in the manifest")
        return BundleContents(theme, fonts, image)
    }

    /** Checks `sha256` and `bytes` of a manifest record against [bytes]; returns the hash. */
    private fun checkRecord(
        rec: JsonObject,
        bytes: ByteArray,
    ): String {
        val sha = (rec["sha256"] as? JsonString)?.value ?: fail(BundleProblem.BAD_MANIFEST, "sha256")
        val count = rec["bytes"] as? JsonNumber ?: fail(BundleProblem.BAD_MANIFEST, "bytes")
        if (!SHA256.matches(sha)) fail(BundleProblem.BAD_MANIFEST, "sha256 format")
        if (!count.isIntegral || count.toBigDecimal() != bytes.size.toBigDecimal()) {
            fail(BundleProblem.SIZE_MISMATCH, "byte count")
        }
        if (sha256(bytes) != sha) fail(BundleProblem.HASH_MISMATCH, "sha256 mismatch")
        return sha
    }

    /** Writes a bundle: manifest, theme, then fonts and the image under hash names (§10.3.1). */
    fun write(
        theme: ByteArray,
        fonts: List<ThemeFont>,
        image: ThemeImage?,
    ): ByteArray {
        val files = ArrayList<Pair<String, ByteArray>>()
        val records = ArrayList<JsonValue>()
        for (f in fonts) {
            val path = "fonts/${f.storeName}"
            files += path to f.bytes
            records += record(path, "font", f.bytes)
        }
        if (image != null) {
            val path = "images/${image.sha256}.${image.extension}"
            files += path to image.bytes
            records += record(path, "wallpaper", image.bytes)
        }
        val manifest =
            JsonObject(
                linkedMapOf(
                    "bundleFormat" to JsonString(FORMAT),
                    "bundleVersion" to JsonNumber.of(VERSION),
                    "theme" to
                        JsonObject(
                            linkedMapOf(
                                "path" to JsonString(THEME),
                                "sha256" to JsonString(sha256(theme)),
                                "bytes" to JsonNumber.of(theme.size),
                            ),
                        ),
                    "files" to JsonArray(records),
                ),
            )
        return BoundedZip.write(listOf(MANIFEST to JsonWriter.prettyBytes(manifest), THEME to theme) + files)
    }

    private fun record(
        path: String,
        role: String,
        bytes: ByteArray,
    ): JsonObject =
        JsonObject(
            linkedMapOf(
                "path" to JsonString(path),
                "role" to JsonString(role),
                "sha256" to JsonString(sha256(bytes)),
                "bytes" to JsonNumber.of(bytes.size),
            ),
        )

    private val SHA256 = Regex("^[0-9a-f]{64}$")

    fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = CharArray(d.size * 2)
        for (i in d.indices) {
            val v = d[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
