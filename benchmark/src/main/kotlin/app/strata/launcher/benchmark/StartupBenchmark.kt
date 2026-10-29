package app.strata.launcher.benchmark

import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Empty cold-start benchmark: proves the measurement pipeline (M0). No budget yet; the NFR-PERF budgets are
 * enforced from M1 on RD-1. Run with `./gradlew :benchmark:connectedBenchmarkAndroidTest` on a device.
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

    private companion object {
        const val TARGET_PACKAGE = "app.strata.launcher"
        const val ITERATIONS = 5
    }
}
