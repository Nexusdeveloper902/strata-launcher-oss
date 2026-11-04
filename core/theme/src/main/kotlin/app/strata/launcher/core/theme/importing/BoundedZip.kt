package app.strata.launcher.core.theme.importing

import app.strata.launcher.core.common.Outcome
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Archive limits of THEME_SYSTEM §10.3.3. [entryCap] gives the decompressed cap of one entry by name. */
data class ZipLimits(
    val maxArchiveBytes: Int = 24 * MIB,
    val maxEntries: Int = 8,
    val maxTotalBytes: Long = 24L * MIB,
    val maxRatio: Int = 200,
    val entryCap: (String) -> Long = { 24L * MIB },
    /** Entry-name allow-list, checked before any byte of the entry is decompressed. */
    val acceptName: (String) -> Boolean = { true },
) {
    companion object {
        const val MIB = 1024 * 1024
    }
}

/** One decompressed file entry. */
class ZipFileEntry(val name: String, val bytes: ByteArray)

/**
 * A bounded zip reader for untrusted bundles (THEME_SYSTEM §9, §10.3.3). It works on the central directory,
 * so entries written with data descriptors are read correctly, and it checks every claim against the bytes:
 *
 * - ZIP64 records or fields, encryption flags and methods other than stored/deflate are rejected;
 * - decompression is counted on the actual output: an entry stops at its cap, at the ratio limit (200) and at
 *   the archive total, whatever its header says; the output must then match the claimed size and CRC-32;
 * - entry names are returned as data only. Callers match them against an allow-list and never use them as paths.
 *
 * Directory entries (`name/`) are skipped. Pure Kotlin (java.util.zip's [Inflater] only).
 */
object BoundedZip {
    fun read(
        archive: ByteArray,
        limits: ZipLimits,
    ): Outcome<List<ZipFileEntry>, ImportError.Bundle> =
        try {
            Outcome.Ok(Reader(archive, limits).readAll())
        } catch (e: BundleException) {
            Outcome.Err(ImportError.Bundle(e.problem, e.message ?: e.problem.name))
        }

    /** Writes [files] (name → bytes) deflated, in order; no ZIP64 (bundles are far below 4 GiB). */
    fun write(files: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in files) {
                val e = ZipEntry(name)
                // Fixed timestamp: the same theme exports to the same bytes (stable hashes, reproducible bundles).
                e.time = FIXED_TIME
                zip.putNextEntry(e)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** 2020-01-01 00:00 local; zip times have 2-second resolution and no zone. */
    private const val FIXED_TIME = 1_577_836_800_000L

    internal class BundleException(val problem: BundleProblem, msg: String) : RuntimeException(msg)

    private class Reader(val b: ByteArray, val limits: ZipLimits) {
        private var total = 0L

        private fun fail(
            p: BundleProblem,
            msg: String,
        ): Nothing = throw BundleException(p, msg)

        private fun u16(at: Int): Int {
            if (at < 0 || at + 2 > b.size) fail(BundleProblem.DAMAGED, "truncated")
            return (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
        }

        private fun u32(at: Int): Long {
            if (at < 0 || at + 4 > b.size) fail(BundleProblem.DAMAGED, "truncated")
            return u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        }

        fun readAll(): List<ZipFileEntry> {
            if (b.size > limits.maxArchiveBytes) fail(BundleProblem.TOO_LARGE, "archive over ${limits.maxArchiveBytes}")
            val eocd = findEocd()
            if (eocd >= 20 && u32(eocd - 20) == SIG_ZIP64_LOCATOR) fail(BundleProblem.ZIP64, "zip64 locator")
            val disk = u16(eocd + 4)
            val cdDisk = u16(eocd + 6)
            val onDisk = u16(eocd + 8)
            val count = u16(eocd + 10)
            val cdSize = u32(eocd + 12)
            val cdOffset = u32(eocd + 16)
            if (onDisk == 0xFFFF || count == 0xFFFF || cdSize == MAX32 || cdOffset == MAX32) {
                fail(BundleProblem.ZIP64, "zip64 end record")
            }
            if (disk != 0 || cdDisk != 0 || onDisk != count) fail(BundleProblem.DAMAGED, "multi-disk archive")
            // Directory entries are ignored (§10.3.1), so `zip -r` output is fine; the file count is checked below.
            // The central directory itself is still bounded, so a huge entry table is not walked.
            if (count > limits.maxEntries * DIRECTORY_SLACK) fail(BundleProblem.TOO_MANY_ENTRIES, "$count entries")
            if (cdOffset + cdSize > eocd) fail(BundleProblem.DAMAGED, "central directory out of bounds")

            val out = ArrayList<ZipFileEntry>()
            val seen = HashSet<String>()
            var p = cdOffset.toInt()
            repeat(count) {
                if (u32(p) != SIG_CENTRAL) fail(BundleProblem.DAMAGED, "bad central entry")
                val flags = u16(p + 8)
                val method = u16(p + 10)
                val crc = u32(p + 16)
                val csize = u32(p + 20)
                val usize = u32(p + 24)
                val nameLen = u16(p + 28)
                val extraLen = u16(p + 30)
                val commentLen = u16(p + 32)
                val local = u32(p + 42)
                val nameStart = p + 46
                if (nameStart + nameLen + extraLen + commentLen > b.size) fail(BundleProblem.DAMAGED, "truncated")
                val name = asciiName(nameStart, nameLen)
                checkExtra(nameStart + nameLen, extraLen)
                if (flags and (FLAG_ENCRYPTED or FLAG_STRONG_ENCRYPTION) != 0 || method == METHOD_AES) {
                    fail(BundleProblem.ENCRYPTED, "encrypted entry")
                }
                if (csize == MAX32 || usize == MAX32 || local == MAX32) fail(BundleProblem.ZIP64, "zip64 sizes")
                p = nameStart + nameLen + extraLen + commentLen
                if (name.endsWith("/")) return@repeat
                if (out.size >=
                    limits.maxEntries
                ) {
                    fail(BundleProblem.TOO_MANY_ENTRIES, "more than ${limits.maxEntries} files")
                }
                if (!limits.acceptName(name)) fail(BundleProblem.UNEXPECTED_ENTRY, "entry not allowed")
                if (!seen.add(name)) fail(BundleProblem.UNEXPECTED_ENTRY, "duplicate entry name")
                if (method != METHOD_STORED && method != METHOD_DEFLATE) {
                    fail(BundleProblem.UNSUPPORTED_METHOD, "method $method")
                }
                out += ZipFileEntry(name, data(local.toInt(), method, csize, usize, crc, name, cdOffset))
            }
            return out
        }

        private fun findEocd(): Int {
            val min = maxOf(0, b.size - EOCD_MAX_SEARCH)
            var i = b.size - EOCD_SIZE
            while (i >= min) {
                if (u32(i) == SIG_EOCD && i + EOCD_SIZE + u16(i + 20) == b.size) return i
                i--
            }
            fail(BundleProblem.DAMAGED, "no end of central directory")
        }

        /** Names must be printable ASCII: the allow-list regex has nothing else, and a decoder is not needed. */
        private fun asciiName(
            at: Int,
            len: Int,
        ): String {
            if (len == 0 || len > MAX_NAME) fail(BundleProblem.UNEXPECTED_ENTRY, "bad name length")
            val chars = CharArray(len)
            for (i in 0 until len) {
                val c = b[at + i].toInt() and 0xff
                if (c < 0x20 || c > 0x7e) fail(BundleProblem.UNEXPECTED_ENTRY, "non-ASCII entry name")
                chars[i] = c.toChar()
            }
            return String(chars)
        }

        private fun checkExtra(
            at: Int,
            len: Int,
        ) {
            var q = at
            val end = at + len
            while (q + 4 <= end) {
                val id = u16(q)
                val size = u16(q + 2)
                if (id == EXTRA_ZIP64) fail(BundleProblem.ZIP64, "zip64 extra field")
                if (id == EXTRA_AES) fail(BundleProblem.ENCRYPTED, "AES extra field")
                q += 4 + size
            }
        }

        private fun data(
            local: Int,
            method: Int,
            csize: Long,
            usize: Long,
            crc: Long,
            name: String,
            cdOffset: Long,
        ): ByteArray {
            if (u32(local) != SIG_LOCAL) fail(BundleProblem.DAMAGED, "bad local header")
            val lflags = u16(local + 6)
            if (lflags and (FLAG_ENCRYPTED or FLAG_STRONG_ENCRYPTION) != 0) fail(BundleProblem.ENCRYPTED, "encrypted")
            if (u16(local + 8) != method) fail(BundleProblem.DAMAGED, "method mismatch")
            val nameLen = u16(local + 26)
            val extraLen = u16(local + 28)
            checkExtra(local + 30 + nameLen, extraLen)
            val start = local.toLong() + 30 + nameLen + extraLen
            if (start + csize > cdOffset) fail(BundleProblem.DAMAGED, "entry data out of bounds")
            val cap = minOf(limits.entryCap(name), limits.maxTotalBytes - total)
            if (usize > cap) fail(BundleProblem.TOO_LARGE, "entry over its limit")
            val bytes =
                when (method) {
                    METHOD_STORED -> {
                        if (csize != usize) fail(BundleProblem.DAMAGED, "stored size mismatch")
                        b.copyOfRange(start.toInt(), (start + csize).toInt())
                    }

                    else -> {
                        inflate(start.toInt(), csize.toInt(), cap)
                    }
                }
            if (bytes.size.toLong() != usize) fail(BundleProblem.DAMAGED, "size does not match header")
            val c = CRC32()
            c.update(bytes)
            if (c.value != crc) fail(BundleProblem.DAMAGED, "CRC mismatch")
            total += bytes.size
            return bytes
        }

        private fun inflate(
            start: Int,
            csize: Int,
            cap: Long,
        ): ByteArray {
            val ratioCap = csize.toLong().coerceAtLeast(1) * limits.maxRatio
            val inflater = Inflater(true)
            try {
                inflater.setInput(b, start, csize)
                val out = ByteArrayOutputStream()
                val buf = ByteArray(BUFFER)
                var produced = 0L
                while (!inflater.finished()) {
                    val n =
                        try {
                            inflater.inflate(buf)
                        } catch (e: DataFormatException) {
                            fail(BundleProblem.DAMAGED, "bad deflate data")
                        }
                    if (n == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) {
                            fail(BundleProblem.DAMAGED, "truncated deflate data")
                        }
                        continue
                    }
                    produced += n
                    // Actual bytes, never header claims (§9): stop as soon as a limit is crossed.
                    if (produced > ratioCap) fail(BundleProblem.ZIP_BOMB, "ratio over ${limits.maxRatio}")
                    if (produced > cap) fail(BundleProblem.TOO_LARGE, "entry over its limit")
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }
    }

    private const val SIG_LOCAL = 0x04034b50L
    private const val SIG_CENTRAL = 0x02014b50L
    private const val SIG_EOCD = 0x06054b50L
    private const val SIG_ZIP64_LOCATOR = 0x07064b50L
    private const val MAX32 = 0xFFFFFFFFL
    private const val EOCD_SIZE = 22
    private const val EOCD_MAX_SEARCH = EOCD_SIZE + 0xFFFF
    private const val FLAG_ENCRYPTED = 0x0001
    private const val FLAG_STRONG_ENCRYPTION = 0x0040
    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATE = 8
    private const val METHOD_AES = 99
    private const val EXTRA_ZIP64 = 0x0001
    private const val EXTRA_AES = 0x9901
    private const val MAX_NAME = 255

    /** Central entries allowed per file entry (directories such as `fonts/` and `images/` come on top). */
    private const val DIRECTORY_SLACK = 4
    private const val BUFFER = 16 * 1024
}
