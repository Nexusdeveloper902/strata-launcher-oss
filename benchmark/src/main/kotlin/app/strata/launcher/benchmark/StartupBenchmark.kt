package app.strata.launcher.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.testing.fixtures.perf.PerfBudgets
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * Start-up (NFR-PERF-01). On a physical device each test then checks the M9 budget ([BudgetGate]); on an emulator it
 * is a correctness check only. Run on RD-1 / the A56: docs/release/RELEASE_GATE.md.
 *
 * - T-PERF-01: cold start via the Home intent, 10 iterations, `timeToFullDisplay` median ≤ 700 ms, p90 ≤ 1 100 ms.
 *   The `strata.*` sections (Application, graph, HomeActivity.onCreate, theme boot) are reported for diagnosis.
 * - T-PERF-02: warm return. Strata is in the background behind Settings; the Home intent brings it back.
 *   `timeToInitialDisplay` median ≤ 150 ms.
 * - T-PERF-12: T-PERF-01 without AOT (`CompilationMode.None`) and with the shipped Baseline Profile; the effect is
 *   recorded (the gate is T-PERF-01).
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @get:Rule
    val test = TestName()

    @OptIn(ExperimentalMetricApi::class)
    private fun startupSections(): List<Metric> =
        listOf(
            "strata.app.onCreate",
            "strata.graph.create",
            "strata.home.onCreate",
            "strata.home.onStart",
            "strata.home.onResume",
            "strata.themeBoot",
        ).map { TraceSectionMetric(it, TraceSectionMetric.Mode.First) }

    @Test
    fun tPerf01_coldStart() {
        coldStart(CompilationMode.DEFAULT, iterations = 10, withSections = true)
        BudgetGate.check(
            javaClass,
            test.methodName,
            BudgetGate.medianAtMost("timeToFullDisplayMs", PerfBudgets.COLD_START_MEDIAN_MS, "T-PERF-01"),
            BudgetGate.percentileAtMost("timeToFullDisplayMs", 90.0, PerfBudgets.COLD_START_P90_MS, "T-PERF-01"),
        )
    }

    @Test
    fun tPerf02_warmReturn() {
        rule.measureRepeated(
            packageName = Strata.TARGET,
            metrics = listOf(StartupTimingMetric()),
            iterations = 10,
            startupMode = null,
            setupBlock = {
                homeReady()
                // Put Strata in the background behind another app (T-PERF-02 "behind a fixture app").
                device.executeShellCommand("am start -W -a android.settings.SETTINGS")
                device.waitForIdle()
            },
        ) {
            startHome()
        }
        BudgetGate.check(
            javaClass,
            test.methodName,
            BudgetGate.medianAtMost("timeToInitialDisplayMs", PerfBudgets.WARM_RETURN_MEDIAN_MS, "T-PERF-02"),
        )
    }

    /** T-PERF-12, "without": JIT only. Recorded, not gated. */
    @Test
    fun tPerf12_coldStartNoProfile() = coldStart(CompilationMode.None(), iterations = 10)

    /** T-PERF-12, "with": the APK's Baseline Profile applied (fails if the APK has none). Recorded, not gated. */
    @Test
    fun tPerf12_coldStartBaselineProfile() =
        coldStart(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require), iterations = 10)

    private fun coldStart(
        mode: CompilationMode,
        iterations: Int,
        withSections: Boolean = false,
    ) = rule.measureRepeated(
        packageName = Strata.TARGET,
        metrics = listOf<Metric>(StartupTimingMetric()) + if (withSections) startupSections() else emptyList(),
        compilationMode = mode,
        iterations = iterations,
        startupMode = StartupMode.COLD,
        // First-run onboarding is completed once here; the process is then killed before each measured start.
        setupBlock = { homeReady() },
    ) {
        startHome()
        // timeToFullDisplay ends at reportFullyDrawn (the list snapshot is composed); keep tracing until home is up.
        device.findOrFail(Strata.ALL_APPS)
    }
}
