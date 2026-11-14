package app.strata.launcher.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.strata.launcher.testing.fixtures.perf.PerfBudgets
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * List smoothness and change propagation (NFR-PERF-02, NFR-PERF-06) on RD-1 with `APPS500` (or the owner's own apps,
 * recorded in the report). Budgets are checked on a physical device only ([BudgetGate]).
 *
 * T-PERF-11 is this class on RD-2 at 120 Hz: the jank check is deadline-based, so the 8.3 ms budget applies by itself.
 */
@RunWith(AndroidJUnit4::class)
class ListBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @get:Rule
    val test = TestName()

    /** T-PERF-03: 5 flings top↔bottom; janky frames ≤ 1 %, no frame > 50 ms. */
    @Test
    fun tPerf03_listFling() {
        rule.measureRepeated(
            packageName = Strata.TARGET,
            metrics = listOf(FrameTimingMetric()),
            iterations = 5,
            setupBlock = { openList() },
        ) {
            val list = device.findOrFail(Strata.LIST)
            list.setGestureMargin(device.displayWidth / 5)
            repeat(5) { i -> list.fling(if (i % 2 == 0) Direction.DOWN else Direction.UP) }
        }
        BudgetGate.check(
            javaClass,
            test.methodName,
            BudgetGate.frames(PerfBudgets.FLING_JANK_FRACTION, PerfBudgets.LONGEST_FRAME_MS, "T-PERF-03"),
        )
    }

    /** T-PERF-04: full A→Z index scrub ×3; janky ≤ 3 %, no frame > 50 ms. */
    @Test
    fun tPerf04_indexScrub() {
        rule.measureRepeated(
            packageName = Strata.TARGET,
            metrics = listOf(FrameTimingMetric()),
            iterations = 3,
            setupBlock = { openList() },
        ) {
            val b = device.findOrFail(Strata.INDEX).visibleBounds
            device.swipe(b.centerX(), b.top + b.height() / 10, b.centerX(), b.bottom - b.height() / 10, 120)
        }
        BudgetGate.check(
            javaClass,
            test.methodName,
            BudgetGate.frames(PerfBudgets.SCRUB_JANK_FRACTION, PerfBudgets.LONGEST_FRAME_MS, "T-PERF-04"),
        )
    }

    /**
     * T-PERF-10: install the "Zz Test" fixture ×10; from the `strata.onPackageAdded` trace to the first
     * `strata.rowComposed` after it ≤ 1 s. Needs the fixture APK pushed to [FIXTURE_APK] (docs/impl-notes/M1.md).
     */
    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun tPerf10_changePropagation() {
        val shell = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assumeTrue(
            "push the Zz fixture APK to $FIXTURE_APK",
            shell.executeShellCommand("ls $FIXTURE_APK").trim() == FIXTURE_APK,
        )
        rule.measureRepeated(
            packageName = Strata.TARGET,
            metrics = listOf(SliceGapMetric("strata.onPackageAdded", "strata.rowComposed", PROPAGATION)),
            iterations = 10,
            setupBlock = {
                device.executeShellCommand("pm uninstall $FIXTURE_PACKAGE")
                openList()
                // Park the list at its end, where "Zz Test" is composed as soon as it exists.
                device.scrollListToEnd()
            },
        ) {
            device.executeShellCommand("pm install -r $FIXTURE_APK")
            val row = device.wait(Until.hasObject(By.text("Zz Test")), Strata.TIMEOUT_MS)
            assertTrue("Zz Test row did not appear", row == true)
            device.executeShellCommand("pm uninstall $FIXTURE_PACKAGE")
        }
        BudgetGate.check(
            javaClass,
            test.methodName,
            BudgetGate.maxAtMost("${PROPAGATION}Ms", PerfBudgets.CHANGE_PROPAGATION_MS, "T-PERF-10"),
        )
    }

    private companion object {
        const val PROPAGATION = "strata.packageAddedToRow"
        const val FIXTURE_PACKAGE = "app.strata.fixture.zz"
        const val FIXTURE_APK = "/data/local/tmp/strata-fixture-zz.apk"
    }
}
