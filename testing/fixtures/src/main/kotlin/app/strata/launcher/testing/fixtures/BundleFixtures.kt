package app.strata.launcher.testing.fixtures

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * Theme bundle fixtures (TEST_PLAN T-THEME-28/29): a structurally valid font, image headers, well-formed bundles and
 * the hostile corpus of T-THEME-29. Everything is generated here (no third-party files). Independent of `:core:theme`
 * on purpose, so the codec is tested against bytes it did not write.
 */
object BundleFixtures {
    const val THEME_ID = "0b0e5c1a-1111-4222-8333-444455556666"

    fun sha256(b: ByteArray): String =
        MessageDigest
            .getInstance(
                "SHA-256",
            ).digest(b)
            .joinToString("") { "%02x".format(it) }

    /**
     * A minimal sfnt with the six required tables (`cmap head hhea hmtx maxp name`), each 4 bytes of zeros. It passes
     * the structural check; a platform loader may still refuse it (use a real font for device tests).
     * [version] `0x00010000` (TrueType) or `OTTO`.
     */
    fun font(
        version: Int = 0x00010000,
        tables: List<String> = listOf("cmap", "head", "hhea", "hmtx", "maxp", "name"),
        badOffset: Boolean = false,
        salt: Int = 0,
    ): ByteArray {
        val n = tables.size
        val dir = 12 + 16 * n
        val buf = ByteBuffer.allocate(dir + 4 * n + 4).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(version)
        buf.putShort(n.toShort())
        buf.putShort(0)
        buf.putShort(0)
        buf.putShort(0)
        tables.forEachIndexed { i, t ->
            buf.put(t.toByteArray(Charsets.ISO_8859_1))
            buf.putInt(0)
            buf.putInt(if (badOffset && i == 0) 1_000_000 else dir + 4 * i)
            buf.putInt(4)
        }
        repeat(n) { buf.putInt(0) }
        buf.putInt(salt)
        return buf.array()
    }

    /** `wOF2` header: WOFF2 is rejected (§10.3.3). */
    fun woff2(): ByteArray = "wOF2".toByteArray() + ByteArray(60)

    /** A RIFF/WEBP file with one `VP8L` chunk declaring [w] × [h] (header-only; not decodable). */
    fun webpHeader(
        w: Int,
        h: Int,
        exif: Boolean = false,
    ): ByteArray {
        val vp8l = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        vp8l.put(0x2f)
        vp8l.putInt(((w - 1) and 0x3FFF) or (((h - 1) and 0x3FFF) shl 14))
        val chunks = ByteArrayOutputStream()
        chunks.write(chunk("VP8L", vp8l.array()))
        if (exif) chunks.write(chunk("EXIF", ByteArray(8)))
        val body = "WEBP".toByteArray() + chunks.toByteArray()
        return "RIFF".toByteArray() + le32(body.size) + body
    }

    /** A PNG signature + IHDR declaring [w] × [h] (header-only). */
    fun pngHeader(
        w: Int,
        h: Int,
    ): ByteArray {
        val b = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN)
        b.put(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        b.putInt(13)
        b.put("IHDR".toByteArray())
        b.putInt(w)
        b.putInt(h)
        b.put(byteArrayOf(8, 6, 0, 0, 0))
        b.putInt(0)
        return b.array()
    }

    private fun chunk(
        id: String,
        data: ByteArray,
    ): ByteArray {
        val pad = if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
        return id.toByteArray() + le32(data.size) + data + pad
    }

    private fun le32(v: Int): ByteArray =
        ByteBuffer
            .allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(v)
            .array()

    /** A theme document referencing [fontSha] (user font) and [imageSha] (wallpaper image), if given. */
    fun theme(
        fontSha: String? = null,
        imageSha: String? = null,
        name: String = "Bundled",
        id: String = THEME_ID,
    ): ByteArray {
        val members = mutableListOf("\"schemaVersion\": 1", "\"id\": \"$id\"", "\"name\": \"$name\"")
        if (imageSha != null) {
            members +=
                "\"wallpaper\": {\"show\": true, \"image\": {\"sha256\": \"$imageSha\", \"mediaType\": \"image/webp\"}}"
        }
        if (fontSha != null) members += "\"typography\": {\"family\": {\"kind\": \"user\", \"sha256\": \"$fontSha\"}}"
        return ("{\n  " + members.joinToString(",\n  ") + "\n}\n").toByteArray()
    }

    /** One archive entry; [listed] false keeps it out of the manifest. */
    class Entry(
        val name: String,
        val bytes: ByteArray,
        val role: String? = null,
        val listed: Boolean = true,
        val declaredSha: String? = null,
        val stored: Boolean = false,
    )

    fun manifest(
        theme: ByteArray,
        files: List<Entry>,
        version: Int = 1,
    ): ByteArray {
        val records =
            files.filter { it.listed }.joinToString(",\n") {
                "    {\"path\": \"${it.name}\", \"role\": \"${it.role}\", \"sha256\": \"${it.declaredSha ?: sha256(
                    it.bytes,
                )}\", " +
                    "\"bytes\": ${it.bytes.size}}"
            }
        return (
            "{\n  \"bundleFormat\": \"strata-theme-bundle\",\n  \"bundleVersion\": $version,\n" +
                "  \"theme\": {\"path\": \"theme.json\", \"sha256\": \"${sha256(
                    theme,
                )}\", \"bytes\": ${theme.size}},\n" +
                "  \"files\": [\n$records\n  ]\n}\n"
        ).toByteArray()
    }

    /** A zip of [entries] in order (deflated unless an entry is [Entry.stored]). */
    fun zip(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for (e in entries) {
                val ze = ZipEntry(e.name)
                if (e.stored) {
                    ze.method = ZipEntry.STORED
                    ze.size = e.bytes.size.toLong()
                    ze.compressedSize = e.bytes.size.toLong()
                    ze.crc = CRC32().apply { update(e.bytes) }.value
                }
                z.putNextEntry(ze)
                z.write(e.bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A well-formed bundle: theme + optional font and image (listed with correct hashes). */
    fun bundle(
        font: ByteArray? = font(),
        image: ByteArray? = webpHeader(64, 32),
        manifestVersion: Int = 1,
        extra: List<Entry> = emptyList(),
        themeBytes: ByteArray? = null,
    ): ByteArray {
        val files = ArrayList<Entry>()
        val fontSha = font?.let(::sha256)
        val imageSha = image?.let(::sha256)
        if (font != null) files += Entry("fonts/$fontSha.ttf", font, "font")
        if (image != null) files += Entry("images/$imageSha.webp", image, "wallpaper")
        files += extra
        val theme = themeBytes ?: theme(fontSha, imageSha)
        return zip(
            listOf(Entry("manifest.json", manifest(theme, files, manifestVersion)), Entry("theme.json", theme)) + files,
        )
    }

    // ---- T-THEME-29 hostile corpus ---------------------------------------------------------------

    /** 16 MiB of zeros as the image entry: deflate ratio ≈ 1000 > 200. */
    fun zipBomb(): ByteArray {
        val zeros = ByteArray(16 * 1024 * 1024)
        val sha = sha256(zeros)
        val e = Entry("images/$sha.webp", zeros, "wallpaper")
        val theme = theme(imageSha = sha)
        return zip(listOf(Entry("manifest.json", manifest(theme, listOf(e))), Entry("theme.json", theme), e))
    }

    /** Entry names that try to escape the extraction directory. */
    fun zipSlip(): ByteArray =
        bundle(
            extra = listOf(Entry("../../files/themes/user/evil.strata-theme.json", theme(), "font")),
        )

    fun unlistedEntry(): ByteArray {
        val f = font(salt = 7)
        return bundle(extra = listOf(Entry("fonts/${sha256(f)}.ttf", f, "font", listed = false)))
    }

    fun hashMismatch(): ByteArray {
        val f = font()
        val sha = sha256(f)
        val wrong = sha.replaceRange(0, 1, if (sha[0] == '0') "1" else "0")
        val e = Entry("fonts/$sha.ttf", f, "font", declaredSha = wrong)
        val theme = theme(fontSha = sha)
        return zip(listOf(Entry("manifest.json", manifest(theme, listOf(e))), Entry("theme.json", theme), e))
    }

    /** A valid bundle with the "encrypted" flag (bit 0) set on every entry, central and local. */
    fun encryptedEntry(): ByteArray {
        val b = bundle()
        patchHeaders(b) { buf, at, central -> buf.putShort(at + (if (central) 8 else 6), 1) }
        return b
    }

    /** A valid bundle whose end record points to ZIP64 values (offset 0xFFFFFFFF). */
    fun zip64(): ByteArray {
        val b = bundle()
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = b.size - 22
        buf.putInt(eocd + 16, -1)
        return b
    }

    /** 25 MiB of incompressible data, stored. */
    fun oversizeArchive(): ByteArray {
        val noise = Random(29).nextBytes(25 * 1024 * 1024)
        return zip(listOf(Entry("images/${sha256(noise)}.png", noise, "wallpaper", stored = true)))
    }

    fun woff2Font(): ByteArray = bundleWithFontBytes(woff2())

    fun badTablesFont(): ByteArray = bundleWithFontBytes(font(badOffset = true))

    fun missingTableFont(): ByteArray =
        bundleWithFontBytes(
            font(tables = listOf("cmap", "head", "hhea", "hmtx", "maxp")),
        )

    private fun bundleWithFontBytes(f: ByteArray): ByteArray = bundle(font = f, image = null)

    fun image9000px(): ByteArray = bundle(font = null, image = webpHeader(9000, 100))

    /** 50 MP within the per-side limit: 8000 × 6250. */
    fun image50mp(): ByteArray = bundle(font = null, image = webpHeader(8000, 6250))

    private fun patchHeaders(
        b: ByteArray,
        patch: (ByteBuffer, Int, Boolean) -> Unit,
    ) {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i + 4 <= b.size) {
            when (buf.getInt(i)) {
                0x04034b50 -> patch(buf, i, false)
                0x02014b50 -> patch(buf, i, true)
            }
            i++
        }
    }
}
