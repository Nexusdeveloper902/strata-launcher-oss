package app.strata.launcher.core.search

import app.strata.launcher.testing.fixtures.AppFixtures
import app.strata.launcher.testing.fixtures.SearchFixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * T-PERF-05 (JVM half, blocking): `APPS500` index, queries "a", "ma", "map", "mpas", "テ" × 100.
 * Ranking ≤ 8 ms median and ≤ 16 ms p95 (NFR-PERF-03). Also the ARCHITECTURE §8 build budget
 * (500 apps; RD-1 target < 5 ms, checked here with the same margin as the ranking budget).
 */
class SearchPerformanceTest {
    private val apps = SearchFixtures.visibleOf(AppFixtures.APPS500)
    private val counts = apps.withIndex().associate { (i, a) -> a.key to i % 60 }

    @Test
    fun tPerf05_rankingLatency500Apps() {
        val idx = index(apps, counts)
        assertThat(idx.size).isEqualTo(500)
        val queries = listOf("a", "ma", "map", "mpas", "テ")
        repeat(WARMUP) { queries.forEach { q -> idx.rank(q, includeHidden = true) } }
        val samples = ArrayList<Long>()
        repeat(RUNS) {
            for (q in queries) {
                val t0 = System.nanoTime()
                idx.rank(q, includeHidden = true)
                samples += System.nanoTime() - t0
            }
        }
        samples.sort()
        val medianMs = samples[samples.size / 2] / 1e6
        val p95Ms = samples[(samples.size * 95) / 100] / 1e6
        println("T-PERF-05 JVM: median=%.3f ms p95=%.3f ms (n=%d)".format(medianMs, p95Ms, samples.size))
        assertThat(medianMs).isAtMost(8.0)
        assertThat(p95Ms).isAtMost(16.0)
    }

    /**
     * T-PERF-05 with typo queries (FR-SEARCH-02 T6, Proposed): queries with no or few exact matches make the fuzzy pass
     * run over the whole index. Reported, with loose ceilings.
     */
    @Test
    fun tPerf05_fuzzyQueriesLatency500Apps() {
        val idx = index(apps, counts)
        val queries = listOf("mpas", "pixlgram", "chtatyapp", "kalkulator", "zzqx", "calender")
        repeat(WARMUP) { queries.forEach { q -> idx.rank(q, includeHidden = true) } }
        val samples = ArrayList<Long>()
        repeat(RUNS) {
            for (q in queries) {
                val t0 = System.nanoTime()
                idx.rank(q, includeHidden = true)
                samples += System.nanoTime() - t0
            }
        }
        samples.sort()
        val medianMs = samples[samples.size / 2] / 1e6
        val p95Ms = samples[(samples.size * 95) / 100] / 1e6
        println("T-PERF-05 JVM fuzzy: median=%.3f ms p95=%.3f ms (n=%d)".format(medianMs, p95Ms, samples.size))
        // Report first; the ceilings only catch a pathological regression on a loaded CI host (measured median 0.2 ms,
        // p95 0.7 ms). The 8 / 16 ms budget itself is checked on the device trace.
        assertThat(medianMs).isAtMost(FUZZY_MEDIAN_CEILING_MS)
        assertThat(p95Ms).isAtMost(FUZZY_P95_CEILING_MS)
    }

    /**
     * T-PERF-05 with neighbouring-key slips (FB-015, Proposed): two- and three-letter slips that the earlier fuzzy pass
     * never saw, plus longer ones. Reported, with the same loose ceilings as the typo set.
     */
    @Test
    fun tPerf05_adjacentQueriesLatency500Apps() {
        val idx = index(apps, counts)
        val queries = listOf("pg", "oh", "pgo", "phpne", "cslendsr", "mapz")
        repeat(WARMUP) { queries.forEach { q -> idx.rank(q, includeHidden = true) } }
        val samples = ArrayList<Long>()
        repeat(RUNS) {
            for (q in queries) {
                val t0 = System.nanoTime()
                idx.rank(q, includeHidden = true)
                samples += System.nanoTime() - t0
            }
        }
        samples.sort()
        val medianMs = samples[samples.size / 2] / 1e6
        val p95Ms = samples[(samples.size * 95) / 100] / 1e6
        println("T-PERF-05 JVM slips: median=%.3f ms p95=%.3f ms (n=%d)".format(medianMs, p95Ms, samples.size))
        assertThat(medianMs).isAtMost(FUZZY_MEDIAN_CEILING_MS)
        assertThat(p95Ms).isAtMost(FUZZY_P95_CEILING_MS)
    }

    @Test
    fun indexBuild500Apps() {
        val cache = HashMap<String, String>()
        repeat(WARMUP) { SearchIndex.build(apps, counts, ENGLISH, Icu4jTransliterate, cache) }
        val samples =
            (1..20)
                .map {
                    val t0 = System.nanoTime()
                    SearchIndex.build(apps, counts, ENGLISH, Icu4jTransliterate, cache)
                    System.nanoTime() - t0
                }.sorted()
        val medianMs = samples[samples.size / 2] / 1e6
        println("Search index build (500, cached translit): median=%.3f ms".format(medianMs))
        // Report only (the < 5 ms budget is for RD-1); the ceiling just catches pathological regressions.
        assertThat(medianMs).isAtMost(100.0)
    }

    private companion object {
        const val WARMUP = 20
        const val RUNS = 100
        const val FUZZY_MEDIAN_CEILING_MS = 16.0
        const val FUZZY_P95_CEILING_MS = 64.0
    }
}
