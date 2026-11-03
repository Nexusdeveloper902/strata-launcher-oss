package app.strata.launcher.platform.icons

import java.io.File

/**
 * Size cap of the L2 icon directory (ARCHITECTURE §9: LRU by last access, capped at [capBytes]).
 *
 * The directory size is tracked as a running total, so a write costs one directory listing only when the total
 * may exceed the cap (and once, for the first write of the process). It used to list and stat every file after
 * every write: a first scroll through 500 apps cost about 500 × 500 / 2 file stats (docs/perf/optimization-pass-1.md
 * D-1). The total is an estimate between trims: concurrent writes and an overwritten key make it run high, which
 * only triggers an earlier exact trim; files removed behind our back (the system clearing the cache) also make it
 * high. A trim recomputes it exactly and frees down to 90 % of the cap, and trims are serialised with writes.
 */
internal class DiskLru(
    private val dir: File,
    private val capBytes: Long,
) {
    /** A trim frees down to this, so the next writes do not each trigger another directory listing. */
    private val lowWatermark = capBytes / 10 * 9

    /** Bytes in [dir], or [UNKNOWN] before the first listing. Guarded by `this`. */
    private var total = UNKNOWN

    /** A file of [bytes] was just written into [dir]. */
    @Synchronized
    fun onWritten(bytes: Long) {
        if (total == UNKNOWN) {
            trim()
            return
        }
        total += bytes
        if (total > capBytes) trim()
    }

    /**
     * Recomputes the exact size and, if it exceeds the cap, deletes the least recently used files (oldest mtime)
     * until it is at or under the low watermark (90 % of the cap). Serialised with [onWritten], so concurrent
     * writes neither lose increments nor make two trims delete the same overage twice.
     */
    @Synchronized
    fun trim() {
        val files = dir.listFiles()?.filter { it.isFile }
        if (files == null) {
            total = UNKNOWN
            return
        }
        var sum = files.sumOf { it.length() }
        if (sum > capBytes) {
            for (f in files.sortedBy { it.lastModified() }) {
                if (sum <= lowWatermark) break
                val length = f.length()
                if (f.delete()) sum -= length
            }
        }
        total = sum
    }

    /** The tracked total (tests). */
    @Synchronized
    internal fun trackedBytes(): Long = total

    private companion object {
        const val UNKNOWN = -1L
    }
}
