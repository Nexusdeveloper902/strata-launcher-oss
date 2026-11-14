package app.strata.launcher.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-PERF-09 (NFR-PERF-05), the part that needs no hour of screen-off: after home, the list and search have run, Strata
 * owns no scheduled job and no pending alarm. Valid on emulators too, so CI runs it. The 1 h screen-off batterystats
 * pass is `tools/perf/background-cost.sh` on the device.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundCostTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun tPerf09_noJobsOrAlarms() {
        device.executeShellCommand("am force-stop ${Strata.TARGET}")
        device.executeShellCommand(Strata.HOME_START_COMMAND)
        device.passOnboardingAndWaitForHome()
        device.findOrFail(Strata.ALL_APPS).click()
        device.findOrFail(Strata.LIST)
        device.pressBack()
        device.findOrFail(Strata.PILL).click()
        device.findOrFail(Strata.SEARCH_FIELD)
        device.pressBack()
        device.pressBack()
        device.waitForIdle()

        val jobs = jobLines(device.executeShellCommand("dumpsys jobscheduler"), Strata.TARGET)
        val alarms = alarmLines(device.executeShellCommand("dumpsys alarm"), Strata.TARGET)
        BudgetGate.log("T-PERF-09 jobs=${jobs.size} alarms=${alarms.size}")
        assertEquals("T-PERF-09: jobs scheduled by Strata:\n${jobs.joinToString("\n")}", 0, jobs.size)
        assertEquals("T-PERF-09: alarms set by Strata:\n${alarms.joinToString("\n")}", 0, alarms.size)
    }

    internal companion object {
        /** Registered jobs are listed as `JOB #u0a123/7: … <package>/<service>`. */
        fun jobLines(
            dump: String,
            pkg: String,
        ): List<String> = dump.lines().filter { "JOB #" in it && "$pkg/" in it }

        /** Pending alarms are listed as `… Alarm{… <package>}` (one line per alarm). */
        fun alarmLines(
            dump: String,
            pkg: String,
        ): List<String> =
            dump.lines().filter {
                "Alarm{" in it &&
                    Regex("""\b${Regex.escape(pkg)}\b""").containsMatchIn(it)
            }
    }
}
