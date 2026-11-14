package app.strata.launcher.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.strata.launcher.testing.fixtures.perf.BudgetMath
import app.strata.launcher.testing.fixtures.perf.PerfBudgets
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Memory (NFR-PERF-04), measured with `dumpsys meminfo` on the benchmark build. Plain instrumented tests (no
 * Macrobenchmark loop: PSS is one reading of a settled state). The budgets are checked on a physical device only.
 *
 * Not automated: the icon-cache bound (≤ memoryClass/8) needs a debug probe the release-like build does not have; it
 * is enforced by construction (byte-sized LruCache at memoryClass/8, docs/perf/optimization-pass-1.md). LeakCanary is
 * not a dependency; the leak half of T-PERF-08 is the PSS growth bound.
 */
@RunWith(AndroidJUnit4::class)
class MemoryBenchmark {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun freshProcess() {
        device.executeShellCommand("am force-stop ${Strata.TARGET}")
        device.executeShellCommand(Strata.HOME_START_COMMAND)
        device.passOnboardingAndWaitForHome()
    }

    /** T-PERF-07: idle home after scrolling the whole list once; PSS ≤ 150 MB. */
    @Test
    fun tPerf07_pssAfterFullScroll() {
        device.findOrFail(Strata.ALL_APPS).click()
        device.findOrFail(Strata.LIST)
        device.scrollListToEnd()
        device.pressBack()
        device.findOrFail(Strata.ALL_APPS)
        val pss = settledPssKb()
        BudgetGate.log("T-PERF-07 PSS=$pss kB budget<=${PerfBudgets.PSS_MAX_KB} kB")
        if (BudgetGate.enforced) {
            assertTrue("T-PERF-07: PSS $pss kB > ${PerfBudgets.PSS_MAX_KB} kB", pss <= PerfBudgets.PSS_MAX_KB)
        }
    }

    /** T-PERF-08: 50 open/close cycles of search; PSS growth ≤ 5 MB (after 5 warm-up cycles). */
    @Test
    fun tPerf08_searchCyclesDoNotGrow() {
        repeat(WARM_UP) { searchCycle() }
        val before = settledPssKb()
        repeat(CYCLES) { searchCycle() }
        val after = settledPssKb()
        val growth = after - before
        BudgetGate.log(
            "T-PERF-08 PSS before=$before after=$after growth=$growth kB budget<=${PerfBudgets.PSS_GROWTH_MAX_KB}",
        )
        if (BudgetGate.enforced) {
            assertTrue(
                "T-PERF-08: PSS grew $growth kB > ${PerfBudgets.PSS_GROWTH_MAX_KB}",
                growth <= PerfBudgets.PSS_GROWTH_MAX_KB,
            )
        }
    }

    private fun searchCycle() {
        device.findOrFail(Strata.PILL).click()
        device.findOrFail(Strata.SEARCH_FIELD)
        // Back hides the IME and, with an empty query, closes search (INTERACTION_SPEC §5); allow both orders.
        var tries = 0
        while (device.hasObject(By.res(Strata.SEARCH_LAYER)) && tries++ < MAX_BACKS) {
            device.pressBack()
            device.wait(Until.gone(By.res(Strata.SEARCH_LAYER)), BACK_WAIT_MS)
        }
        check(!device.hasObject(By.res(Strata.SEARCH_LAYER))) { "search did not close after $MAX_BACKS Back presses" }
        device.findOrFail(Strata.PILL)
    }

    /** Median of three `TOTAL PSS` readings a second apart, once the app has been idle for a few seconds. */
    private fun settledPssKb(): Long {
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)
        val readings =
            (1..3).map {
                Thread.sleep(1_000)
                pssKb(device.executeShellCommand("dumpsys meminfo ${Strata.TARGET}"))
            }
        return BudgetMath.median(readings.map { it.toDouble() }).toLong()
    }

    internal companion object {
        const val WARM_UP = 5
        const val CYCLES = 50
        const val MAX_BACKS = 3
        const val BACK_WAIT_MS = 2_000L
        const val SETTLE_MS = 3_000L

        /** `TOTAL PSS: n` (API 30+) or the `TOTAL n …` row of the summary table (older releases). */
        fun pssKb(meminfo: String): Long {
            Regex("""TOTAL PSS:\s+(\d+)""").find(meminfo)?.let { return it.groupValues[1].toLong() }
            Regex("""(?m)^\s*TOTAL\s+(\d+)""").find(meminfo)?.let { return it.groupValues[1].toLong() }
            error("no TOTAL PSS in dumpsys meminfo output:\n${meminfo.take(2_000)}")
        }
    }
}
