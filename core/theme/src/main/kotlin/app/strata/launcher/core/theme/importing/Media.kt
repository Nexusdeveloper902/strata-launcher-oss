package app.strata.launcher.core.theme.importing

import app.strata.launcher.core.theme.model.ImageMediaType

/** Font formats a theme may carry (THEME_SYSTEM §10.3.1: `.ttf` / `.otf`). */
enum class FontFormat(val extension: String) { TTF("ttf"), OTF("otf") }

/**
 * Structural font check of THEME_SYSTEM §10.3.3, done before any platform font loader sees the bytes: size cap,
 * an sfnt version of `00 01 00 00`, `OTTO` or `true` (collections and WOFF/WOFF2 rejected), and a sane table
 * directory (1..64 tables, every table inside the file, `cmap head hhea hmtx maxp name` present).
 */
object FontCheck {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_TABLES = 64
    private val REQUIRED = setOf("cmap", "head", "hhea", "hmtx", "maxp", "name")

    /** The format, or null when the bytes are not an acceptable font. */
    fun check(bytes: ByteArray): FontFormat? {
        if (bytes.size > MAX_BYTES || bytes.size < 12) return null
        val format =
            when (tag(bytes, 0)) {
                "\u0000\u0001\u0000\u0000", "true" -> FontFormat.TTF
                "OTTO" -> FontFormat.OTF
                else -> return null
            }
        val numTables = u16(bytes, 4)
        if (numTables < 1 || numTables > MAX_TABLES) return null
        if (12 + numTables * 16 > bytes.size) return null
        val tags = HashSet<String>()
        for (i in 0 until numTables) {
            val rec = 12 + i * 16
            val t = tag(bytes, rec)
            val offset = u32(bytes, rec + 8)
            val length = u32(bytes, rec + 12)
            if (offset + length > bytes.size || offset < 12 + numTables * 16L) return null
            if (!tags.add(t)) return null
        }
        return if (tags.containsAll(REQUIRED)) format else null
    }

    private fun tag(
        b: ByteArray,
        at: Int,
    ): String = String(CharArray(4) { (b[at + it].toInt() and 0xff).toChar() })

    private fun u16(
        b: ByteArray,
        at: Int,
    ): Int = ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)

    private fun u32(
        b: ByteArray,
        at: Int,
    ): Long = (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()
}

/** What the header of an image says (sniffed, never trusted beyond the bounds check). */
data class ImageHeader(
    val type: ImageMediaType,
    val width: Int,
    val height: Int,
    /**
     * WebP only: not a clean, stripped file. True when EXIF, XMP or ICC chunks (or their `VP8X` flags) are present, any
     * chunk outside `VP8 `/`VP8L`/`VP8X`/`ALPH` appears, or bytes follow the RIFF payload. Such a file is re-encoded
     * instead of being stored byte for byte (§10.3.3).
     */
    val hasMetadata: Boolean,
)

/**
 * Bounds-first image checks of THEME_SYSTEM §10.3.3 on the headers only: type sniffed from the magic bytes,
 * dimensions from PNG `IHDR`, the JPEG start-of-frame or the WebP `VP8 `/`VP8L`/`VP8X` chunk. The platform decoder
 * ([ThemeMedia]) repeats the bounds check on what it actually decodes.
 */
object ImageCheck {
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_SIDE = 8192
    const val MAX_PIXELS = 40_000_000L

    fun header(b: ByteArray): ImageHeader? =
        try {
            when {
                b.size >= 24 && b[0] == 0x89.toByte() && String(b, 1, 3, Charsets.ISO_8859_1) == "PNG" -> png(b)
                b.size >= 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> jpeg(b)
                b.size >= 16 && riff(b, 0) == "RIFF" && riff(b, 8) == "WEBP" -> webp(b)
                else -> null
            }
        } catch (e: IndexOutOfBoundsException) {
            null
        }

    fun withinLimits(h: ImageHeader): Boolean =
        h.width in 1..MAX_SIDE && h.height in 1..MAX_SIDE && h.width.toLong() * h.height <= MAX_PIXELS

    private fun png(b: ByteArray): ImageHeader? {
        if (riff(b, 12) != "IHDR") return null
        return ImageHeader(ImageMediaType.PNG, be32(b, 16), be32(b, 20), hasMetadata = false)
    }

    private fun jpeg(b: ByteArray): ImageHeader? {
        var i = 2
        while (i + 9 < b.size) {
            if (b[i] != 0xFF.toByte()) return null
            val marker = b[i + 1].toInt() and 0xff
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) {
                i += 2
                continue
            }
            val len = be16(b, i + 2)
            // SOF0..SOF15 except DHT (C4), JPG (C8) and DAC (CC).
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                return ImageHeader(ImageMediaType.JPEG, be16(b, i + 7), be16(b, i + 5), hasMetadata = true)
            }
            if (len < 2) return null
            i += 2 + len
        }
        return null
    }

    private fun webp(b: ByteArray): ImageHeader? {
        var w = 0
        var h = 0
        // Bytes after the RIFF payload (or a payload that overruns the file) make it unclean.
        val riffEnd = 8L + (le32(b, 4).toLong() and 0xFFFFFFFFL)
        var meta = riffEnd != b.size.toLong()
        val end = minOf(riffEnd, b.size.toLong()).toInt()
        var i = 12
        while (i + 8 <= end) {
            val id = riff(b, i)
            val size = le32(b, i + 4)
            if (size < 0) return null
            val body = i + 8
            if (id !in CLEAN_CHUNKS) meta = true
            when (id) {
                "VP8X" -> {
                    if (b[body].toInt() and VP8X_METADATA_FLAGS != 0) meta = true
                    w = 1 + le24(b, body + 4)
                    h = 1 + le24(b, body + 7)
                }

                "VP8 " -> {
                    if (w == 0) {
                        w = le16(b, body + 6) and 0x3FFF
                        h = le16(b, body + 8) and 0x3FFF
                    }
                }

                "VP8L" -> {
                    if (w == 0) {
                        val bits = le32(b, body + 1)
                        w = (bits and 0x3FFF) + 1
                        h = ((bits ushr 14) and 0x3FFF) + 1
                    }
                }
            }
            i = body + size + (size and 1)
        }
        if (i != end) meta = true
        if (w == 0 || h == 0) return null
        return ImageHeader(ImageMediaType.WEBP, w, h, meta)
    }

    private fun riff(
        b: ByteArray,
        at: Int,
    ): String = String(b, at, 4, Charsets.ISO_8859_1)

    /** Chunks a clean, still WebP may carry (image data and alpha). Everything else forces a re-encode. */
    private val CLEAN_CHUNKS = setOf("VP8 ", "VP8L", "VP8X", "ALPH")

    /** `VP8X` flag bits for ICC (0x20), EXIF (0x08) and XMP (0x04). */
    private const val VP8X_METADATA_FLAGS = 0x2C

    private fun be16(
        b: ByteArray,
        at: Int,
    ): Int = ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)

    private fun be32(
        b: ByteArray,
        at: Int,
    ): Int = (be16(b, at) shl 16) or be16(b, at + 2)

    private fun le16(
        b: ByteArray,
        at: Int,
    ): Int = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

    private fun le24(
        b: ByteArray,
        at: Int,
    ): Int = le16(b, at) or ((b[at + 2].toInt() and 0xff) shl 16)

    private fun le32(
        b: ByteArray,
        at: Int,
    ): Int = le16(b, at) or (le16(b, at + 2) shl 16)
}

/**
 * Removes the metadata chunks (`ICCP`, `EXIF`, `XMP `) from a WebP and clears their `VP8X` flags. Encoders may embed an
 * sRGB ICC profile; without this a re-encoded image would not be "already stripped" and a second import would
 * re-encode it again with new bytes and a new hash (§10.3.3). Returns null when [webp] is not a well-formed RIFF/WEBP.
 */
object WebpStrip {
    private val METADATA = setOf("ICCP", "EXIF", "XMP ")
    private const val FLAG_ICC = 0x20
    private const val FLAG_EXIF = 0x08
    private const val FLAG_XMP = 0x04

    fun strip(webp: ByteArray): ByteArray? {
        if (webp.size < 12 || String(webp, 0, 4, Charsets.ISO_8859_1) != "RIFF") return null
        if (String(webp, 8, 4, Charsets.ISO_8859_1) != "WEBP") return null
        val out = java.io.ByteArrayOutputStream(webp.size)
        out.write(webp, 0, 12)
        var i = 12
        while (i + 8 <= webp.size) {
            val id = String(webp, i, 4, Charsets.ISO_8859_1)
            val size =
                (webp[i + 4].toInt() and 0xff) or ((webp[i + 5].toInt() and 0xff) shl 8) or
                    ((webp[i + 6].toInt() and 0xff) shl 16) or ((webp[i + 7].toInt() and 0xff) shl 24)
            val padded = size + (size and 1)
            if (size < 0 || i + 8 + padded > webp.size) return null
            if (id !in METADATA) {
                val start = out.size()
                out.write(webp, i, 8 + padded)
                if (id == "VP8X" && size >= 1) {
                    val bytes = out.toByteArray()
                    bytes[start + 8] = (bytes[start + 8].toInt() and (FLAG_ICC or FLAG_EXIF or FLAG_XMP).inv()).toByte()
                    out.reset()
                    out.write(bytes)
                }
            }
            i += 8 + padded
        }
        if (i != webp.size) return null
        val result = out.toByteArray()
        val riff = result.size - 8
        result[4] = riff.toByte()
        result[5] = (riff shr 8).toByte()
        result[6] = (riff shr 16).toByte()
        result[7] = (riff shr 24).toByte()
        return result
    }
}

/** Result of preparing a wallpaper image for the image store. */
sealed interface ImageOutcome {
    /** Stored as given: an already stripped WebP within the limits (§10.3.3). */
    data object AsIs : ImageOutcome

    /** Re-encoded to WebP (also strips EXIF/GPS metadata); the hash is recomputed by the caller. */
    class Reencoded(val webp: ByteArray) : ImageOutcome

    data object Rejected : ImageOutcome
}

/**
 * The platform half of the media checks (THEME_SYSTEM §10.3.3), implemented in `:core:data` with
 * `Typeface.Builder` and `BitmapFactory`. Called only after the pure [FontCheck] / [ImageCheck] passed, off main.
 */
interface ThemeMedia {
    /** `Typeface.Builder(file).build()` without fallback is non-null and measures a test string with width > 0. */
    fun fontLoads(
        bytes: ByteArray,
        format: FontFormat,
    ): Boolean

    /** Bounds-first decode with sampling to ≤ display size × 2, re-encoded to WebP unless [ImageOutcome.AsIs]. */
    fun prepareImage(
        bytes: ByteArray,
        header: ImageHeader,
    ): ImageOutcome

    /**
     * Decodes [bytes] (bounds first, within the §10.3.3 limits) sampled down to at most [maxSide] pixels on the longer
     * side and returns its `0xAARRGGBB` pixels, for the create-from-image seed (§13). Null when it does not decode.
     */
    fun samplePixels(
        bytes: ByteArray,
        header: ImageHeader,
        maxSide: Int,
    ): IntArray? = null

    companion object {
        /** Accepts every font and keeps clean WebP as is, re-encodes nothing else (pure JVM tests). */
        val TRUSTING: ThemeMedia =
            object : ThemeMedia {
                override fun fontLoads(
                    bytes: ByteArray,
                    format: FontFormat,
                ): Boolean = true

                override fun prepareImage(
                    bytes: ByteArray,
                    header: ImageHeader,
                ): ImageOutcome =
                    if (header.type == ImageMediaType.WEBP && !header.hasMetadata) {
                        ImageOutcome.AsIs
                    } else {
                        ImageOutcome.Rejected
                    }
            }
    }
}
