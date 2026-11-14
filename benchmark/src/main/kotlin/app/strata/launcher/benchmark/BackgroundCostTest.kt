package app.strata.launcher.benchmark

import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-PERF-09 (NFR-PERF-05), the part that needs no hour of screen-off: after home, the list and search have run, Strata
 * owns no scheduled job and no pending alarm; idle home draws (almost) no frames; and neither idle home nor a screen-off
 * minute costs more than a sliver of CPU time. Valid on emulators too, so CI runs it. The 1 h screen-off batterystats
 * pass is `tools/perf/background-cost.sh` on the device.
 *
 * The thresholds are deliberately generous and count work, not wall time: an infinite animation or a frame loop draws
 * 60–120 frames a second and burns whole seconds of CPU a minute, two to three orders of magnitude above them, while
 * a minute tick of the clock is one or two frames. A slow or loaded emulator does not make idle home draw more.
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

    /** The numeric user this test runs as (shell commands take it explicitly, never `current`). */
    private val userId = Process.myUid() / PER_USER_RANGE

    /** Home in front, onboarding passed, start-up work (icon fades, deferred housekeeping) settled. */
    private fun idleHome() {
        device.executeShellCommand("am force-stop --user $userId ${Strata.TARGET}")
        device.executeShellCommand(
            "am start -W --user $userId -a android.intent.action.MAIN -c android.intent.category.HOME " +
                "-n ${Strata.TARGET}/${Strata.HOME_ACTIVITY}",
        )
        device.passOnboardingAndWaitForHome()
        device.waitForIdle()
        SystemClock.sleep(SETTLE_MS)
    }

    private fun framesRendered(): Int? = framesOf(device.executeShellCommand("dumpsys gfxinfo ${Strata.TARGET}"))

    private fun cpuTicks(): Long? {
        val pid =
            device
                .executeShellCommand("pidof ${Strata.TARGET}")
                .trim()
                .substringBefore(' ')
        if (pid.isEmpty()) return null
        return cpuTicksOf(device.executeShellCommand("cat /proc/$pid/stat"))
    }

    /** T-PERF-09: home at rest for [IDLE_MS] draws at most a clock tick's worth of frames and uses little CPU. */
    @Test
    fun tPerf09_idleHomeDrawsNoFrames() {
        idleHome()
        device.executeShellCommand("dumpsys gfxinfo ${Strata.TARGET} reset")
        val cpuBefore = cpuTicks()
        SystemClock.sleep(IDLE_MS)
        val frames = framesRendered()
        val cpu = cpuTicks()?.let { after -> cpuBefore?.let { after - it } }
        BudgetGate.log("T-PERF-09 idle home ${IDLE_MS / 1000} s: frames=$frames cpuTicks=$cpu")
        assertTrue("T-PERF-09: gfxinfo had no frame count", frames != null)
        assertTrue("T-PERF-09: idle home drew $frames frames in ${IDLE_MS / 1000} s", frames!! <= MAX_IDLE_FRAMES)
        if (cpu != null) assertTrue("T-PERF-09: idle home used $cpu CPU ticks", cpu <= MAX_IDLE_CPU_TICKS)
    }

    /** T-PERF-09: a minute with the screen off costs Strata (process alive, home stopped) almost no CPU time. */
    @Test
    fun tPerf09_screenOffMinuteUsesNoCpu() {
        idleHome()
        val before = cpuTicks()
        try {
            device.executeShellCommand("input keyevent KEYCODE_SLEEP")
            SystemClock.sleep(SCREEN_OFF_MS)
        } finally {
            // Hand the device back as it was: screen on, keyguard (if any) dismissed.
            device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
            device.executeShellCommand("wm dismiss-keyguard")
        }
        val after = cpuTicks()
        val cpu = if (before != null && after != null) after - before else null
        BudgetGate.log("T-PERF-09 screen off ${SCREEN_OFF_MS / 1000} s: cpuTicks=$cpu")
        assertTrue("T-PERF-09: Strata's process was not found", cpu != null)
        assertTrue("T-PERF-09: $cpu CPU ticks with the screen off", cpu!! <= MAX_SCREEN_OFF_CPU_TICKS)
    }

    internal companion object {
        private const val PER_USER_RANGE = 100_000
        private const val SETTLE_MS = 5_000L
        private const val IDLE_MS = 20_000L
        private const val SCREEN_OFF_MS = 60_000L

        /** A minute tick can fall in the window (1–2 frames); 20 s of a 60 Hz loop would be 1 200. */
        private const val MAX_IDLE_FRAMES = 10

        /** `/proc/<pid>/stat` ticks (USER_HZ, 100 on Android): 1.5 s of CPU in 20 s idle; a busy loop uses ~20 s. */
        private const val MAX_IDLE_CPU_TICKS = 150L

        /** 2 s of CPU in a screen-off minute; NFR-PERF-05 asks for none, this leaves room for GC and binder noise. */
        private const val MAX_SCREEN_OFF_CPU_TICKS = 200L

        /** The process's `Total frames rendered: N` from `dumpsys gfxinfo <pkg>` (the first, process-wide, total). */
        fun framesOf(dump: String): Int? =
            Regex("""Total frames rendered:\s*(\d+)""")
                .find(dump)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()

        /** utime + stime (fields 14 and 15) of a `/proc/<pid>/stat` line; the command name may contain spaces. */
        fun cpuTicksOf(stat: String): Long? {
            val rest = stat.substringAfterLast(')', "").trim().split(' ')
            // After ")": field 3 (state) is index 0, so utime (14) is index 11 and stime (15) index 12.
            val utime = rest.getOrNull(UTIME_INDEX)?.toLongOrNull() ?: return null
            val stime = rest.getOrNull(UTIME_INDEX + 1)?.toLongOrNull() ?: return null
            return utime + stime
        }

        private const val UTIME_INDEX = 11

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
