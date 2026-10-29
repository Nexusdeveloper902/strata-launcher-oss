package app.strata.launcher.core.theme

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.json.JsonWriter
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.ThemeResolver
import app.strata.launcher.core.theme.resolve.WallpaperColorsInput
import app.strata.launcher.testing.fixtures.ThemeFixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * T-THEME-44, JVM proxy only. The DoD level is M (Macrobenchmark on RD-1); this test catches algorithmic
 * regressions early (ROADMAP M4 risk: "parser performance at the 256 KiB limit … benchmarking in M4").
 * It checks the same budgets on the host JVM, which is faster than RD-1, so passing here is necessary,
 * not sufficient.
 */
class PerformanceTest {
    /** A valid theme of just under 256 KiB: Graphite plus large extension strings (within every parse limit). */
    private fun bigTheme(): ByteArray {
        var tree = json(String(ThemeFixtures.sample("graphite.strata-theme.json")))
        var i = 0
        while (JsonWriter.prettyBytes(tree).size < 250 * 1024) {
            tree = tree.with("x-blob-$i", JsonObject((0 until 8).associate { "s$it" to JsonString("abcdefghij".repeat(400)) }))
            i++
        }
        return JsonWriter.prettyBytes(tree).also { assertThat(it.size).isAtMost(256 * 1024) }
    }

    private fun medianMillis(
        runs: Int,
        block: () -> Unit,
    ): Double {
        repeat(runs) { block() } // warm-up (JIT)
        val times =
            (0 until runs).map {
                val t0 = System.nanoTime()
                block()
                (System.nanoTime() - t0) / 1e6
            }.sorted()
        return times[times.size / 2]
    }

    @Test
    fun `T-THEME-44 parse, validate and resolve stay within the NFR-THEME-02 budgets on the JVM`() {
        val typical = ThemeFixtures.sample("dusk.strata-theme.json")
        assertThat(typical.size).isLessThan(8 * 1024)
        val big = bigTheme()
        val platform = PlatformInputs(apiLevel = 34, systemDark = false, wallpaperColors = WallpaperColorsInput(0xFF2E7D32.toInt()))
        fun once(bytes: ByteArray) {
            val p = (parser.parse(bytes) as Outcome.Ok).value
            ThemeResolver.resolve(p.document, platform)
        }
        val typicalMs = medianMillis(50) { once(typical) }
        val bigMs = medianMillis(20) { once(big) }
        println("T-THEME-44 JVM median: typical ${"%.2f".format(typicalMs)} ms, 256 KiB ${"%.2f".format(bigMs)} ms")
        assertThat(typicalMs).isAtMost(5.0)
        assertThat(bigMs).isAtMost(30.0)
    }
}
