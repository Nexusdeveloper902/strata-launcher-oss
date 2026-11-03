package app.strata.launcher.platform.icons

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** L2 icon directory cap (ARCHITECTURE §9) and the cache-key hex encoding (docs/perf/optimization-pass-1.md). */
class DiskLruTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun File.put(
        name: String,
        bytes: Int,
        mtime: Long,
    ): File =
        File(this, name).also {
            it.writeBytes(ByteArray(bytes))
            it.setLastModified(mtime)
        }

    @Test
    fun staysUnderTheCapDeletingTheLeastRecentlyUsedFirst() {
        val dir = tmp.newFolder("icons")
        val lru = DiskLru(dir, capBytes = 300)
        dir.put("a.webp", 100, 1_000_000L)
        lru.onWritten(100)
        dir.put("b.webp", 100, 2_000_000L)
        lru.onWritten(100)
        dir.put("c.webp", 100, 3_000_000L)
        lru.onWritten(100)
        assertThat(dir.list()!!.sorted()).containsExactly("a.webp", "b.webp", "c.webp")
        dir.put("d.webp", 100, 4_000_000L)
        lru.onWritten(100)
        // Trimmed to the 90 % watermark (270): two files left, not three.
        assertThat(dir.list()!!.sorted()).containsExactly("c.webp", "d.webp")
        assertThat(lru.trackedBytes()).isEqualTo(200)
    }

    @Test
    fun aWriteAfterATrimDoesNotTrimAgain() {
        val dir = tmp.newFolder("icons")
        val lru = DiskLru(dir, capBytes = 1_000)
        for (i in 0 until 10) {
            dir.put("f$i.webp", 100, 1_000_000L + i)
            lru.onWritten(100)
        }
        dir.put("f10.webp", 100, 2_000_000L)
        lru.onWritten(100) // 1100 > 1000: trims to <= 900
        assertThat(lru.trackedBytes()).isEqualTo(900)
        // A file the tracker never heard of is only seen by a listing; none may happen for the next write.
        dir.put("stranger.webp", 50, 2_500_000L)
        dir.put("f11.webp", 50, 3_000_000L)
        lru.onWritten(50)
        assertThat(lru.trackedBytes()).isEqualTo(950)
        assertThat(dir.list()!!.toList()).contains("stranger.webp")
    }

    @Test
    fun concurrentWritesKeepTheTotalExact() {
        val dir = tmp.newFolder("icons")
        val lru = DiskLru(dir, capBytes = 1_000_000)
        lru.onWritten(0)
        val threads =
            (0 until 8).map { t ->
                Thread { repeat(500) { lru.onWritten(10) } }.also { it.name = "w$t" }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertThat(lru.trackedBytes()).isEqualTo(8 * 500 * 10L)
    }

    @Test
    fun firstWriteMeasuresWhatIsAlreadyThere() {
        val dir = tmp.newFolder("icons")
        dir.put("old1.webp", 200, 1_000_000L)
        dir.put("old2.webp", 200, 2_000_000L)
        val lru = DiskLru(dir, capBytes = 450)
        dir.put("new.webp", 100, 3_000_000L)
        lru.onWritten(100)
        assertThat(dir.list()!!.sorted()).containsExactly("old2.webp", "new.webp")
        assertThat(lru.trackedBytes()).isEqualTo(300)
    }

    @Test
    fun underTheCapNoListingAfterTheFirstWrite() {
        val dir = tmp.newFolder("icons")
        val lru = DiskLru(dir, capBytes = 1_000)
        dir.put("a.webp", 100, 1_000_000L)
        lru.onWritten(100)
        // A file the tracker never heard of: only a listing would see it, and none happens below the cap.
        dir.put("stranger.webp", 50, 1_500_000L)
        dir.put("b.webp", 100, 2_000_000L)
        lru.onWritten(100)
        assertThat(lru.trackedBytes()).isEqualTo(200)
    }

    @Test
    fun hexMatchesFormatPerByte() {
        val digest = MessageDigest.getInstance("SHA-1").digest("v1|a/b|0|1|144|420".toByteArray())
        val expected = digest.joinToString("") { "%02x".format(it) }
        assertThat(SystemIconProvider.hex(digest)).isEqualTo(expected)
        assertThat(SystemIconProvider.hex(byteArrayOf(0, 15, -1, -128, 127))).isEqualTo("000fff807f")
    }
}
