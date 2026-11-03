package app.strata.launcher.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start benchmarks. [coldStartup] proves the measurement pipeline (M0). T-PERF-12 runs T-PERF-01's cold start
 * twice: without any AOT compilation and with the Baseline Profile shipped in the APK, so the profile's effect on
 * the median is recorded. Run with `./gradlew :benchmark:connectedBenchmarkAndroidTest` on a device
 * (docs/perf/optimization-pass-1.md §6).
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            iterations = ITERATIONS,
            startupMode = StartupMode.COLD,
        ) {
            pressHome()
            startActivityAndWait()
        }

    /** T-PERF-12, "without": JIT only (`CompilationMode.None`). */
    @Test
    fun tPerf12_coldStartNoProfile() = coldStart(CompilationMode.None())

    /** T-PERF-12, "with": the APK's Baseline Profile applied (fails if the APK has none). */
    @Test
    fun tPerf12_coldStartBaselineProfile() =
        coldStart(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

    private fun coldStart(mode: CompilationMode) =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = mode,
            iterations = T_PERF_12_ITERATIONS,
            startupMode = StartupMode.COLD,
        ) {
            pressHome()
            startActivityAndWait()
        }

    private companion object {
        const val TARGET_PACKAGE = "app.strata.launcher.benchmark"
        const val ITERATIONS = 5
        const val T_PERF_12_ITERATIONS = 10
    }
}
