package app.strata.launcher.benchmark

import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        device.executeShellCommand("am force-stop --user $userId ${Strata.TARGET}")
        device.executeShellCommand(homeStartCommand())
        device.passOnboardingAndWaitForHome()
        device.clickOrFail(Strata.ALL_APPS)
        device.findOrFail(Strata.LIST)
        device.pressBack()
        device.clickOrFail(Strata.PILL)
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

    private fun homeStartCommand() =
        "am start -W --user $userId -a android.intent.action.MAIN -c android.intent.category.HOME " +
            "-n ${Strata.TARGET}/${Strata.HOME_ACTIVITY}"

    /** Home in front, onboarding passed, start-up work (icon fades, deferred housekeeping) finished. */
    private fun idleHome(): String {
        device.executeShellCommand("am force-stop --user $userId ${Strata.TARGET}")
        device.executeShellCommand(homeStartCommand())
        device.passOnboardingAndWaitForHome()
        device.waitForIdle()
        val pid = pid()
        awaitCpuQuiet(pid)
        return pid
    }

    /** Strata's one process; fails when there is none or more than one (the CPU reading would mean nothing). */
    private fun pid(): String {
        val pids = pidsOf(device.executeShellCommand("pidof ${Strata.TARGET}"))
        assertEquals("T-PERF-09: expected exactly one ${Strata.TARGET} process, found $pids", 1, pids.size)
        return pids.single()
    }

    private fun cpuTicks(pid: String): Long {
        val ticks = cpuTicksOf(device.executeShellCommand("cat /proc/$pid/stat"))
        assertNotNull("T-PERF-09: no CPU time readable for pid $pid", ticks)
        return ticks!!
    }

    /**
     * Waits until start-up work is over: the process's CPU time grows by at most [QUIET_TICKS] in each of
     * [QUIET_SAMPLES] consecutive [QUIET_SAMPLE_MS] samples, or [QUIET_TIMEOUT_MS] passed (then the measurement
     * itself reports whatever is still running).
     */
    private fun awaitCpuQuiet(pid: String) {
        val deadline = SystemClock.uptimeMillis() + QUIET_TIMEOUT_MS
        var last = cpuTicks(pid)
        var quiet = 0
        while (quiet < QUIET_SAMPLES && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(QUIET_SAMPLE_MS)
            val now = cpuTicks(pid)
            quiet = if (now - last <= QUIET_TICKS) quiet + 1 else 0
            last = now
        }
    }

    private fun framesRendered(): Int {
        val frames = framesOf(device.executeShellCommand("dumpsys gfxinfo ${Strata.TARGET}"))
        assertNotNull("T-PERF-09: gfxinfo had no frame count", frames)
        return frames!!
    }

    private fun assertHomeResumed(whenText: String) {
        val dump = device.executeShellCommand("dumpsys activity activities")
        assertTrue(
            "T-PERF-09: home was not the resumed activity $whenText",
            resumedIs(dump, "${Strata.TARGET}/${Strata.HOME_ACTIVITY}"),
        )
    }

    /** CPU ticks used by [pid] while [block] runs; the process must be the same one afterwards. */
    private fun cpuDuring(
        pid: String,
        block: () -> Unit,
    ): Long {
        val before = cpuTicks(pid)
        block()
        assertEquals("T-PERF-09: Strata's process changed during the measurement", pid, pid())
        val used = cpuTicks(pid) - before
        assertTrue("T-PERF-09: negative CPU time ($used)", used >= 0)
        return used
    }

    /**
     * T-PERF-09: home at rest for [IDLE_MS] draws at most a clock tick's worth of frames and uses little CPU. The
     * positive control (a list fling afterwards draws frames) shows the frame counter works on this device.
     */
    @Test
    fun tPerf09_idleHomeDrawsNoFrames() {
        val pid = idleHome()
        assertHomeResumed("before the idle window")
        device.executeShellCommand("dumpsys gfxinfo ${Strata.TARGET} reset")
        val cpu = cpuDuring(pid) { SystemClock.sleep(IDLE_MS) }
        val frames = framesRendered()
        assertHomeResumed("after the idle window")
        BudgetGate.log("T-PERF-09 idle home ${IDLE_MS / 1000} s: frames=$frames cpuTicks=$cpu")
        assertTrue("T-PERF-09: idle home drew $frames frames in ${IDLE_MS / 1000} s", frames <= MAX_IDLE_FRAMES)
        assertTrue("T-PERF-09: idle home used $cpu CPU ticks", cpu <= MAX_IDLE_CPU_TICKS)

        // Positive control: real motion is counted.
        device.executeShellCommand("dumpsys gfxinfo ${Strata.TARGET} reset")
        device.findOrFail(Strata.ALL_APPS).click()
        device.findOrFail(Strata.LIST).fling(Direction.DOWN)
        device.waitForIdle()
        val moved = framesRendered()
        BudgetGate.log("T-PERF-09 control: frames=$moved after opening and flinging the list")
        assertTrue("T-PERF-09: the control drew no frame, so the idle count proves nothing", moved > 0)
        device.pressBack()
    }

    /** T-PERF-09: a minute with the screen off costs Strata (process alive, home stopped) almost no CPU time. */
    @Test
    fun tPerf09_screenOffMinuteUsesNoCpu() {
        val pid = idleHome()
        assertHomeResumed("before the screen went off")
        val cpu =
            cpuDuring(pid) {
                try {
                    device.executeShellCommand("input keyevent KEYCODE_SLEEP")
                    SystemClock.sleep(SCREEN_OFF_MS)
                } finally {
                    // Hand the device back as it was: screen on, keyguard (if any) dismissed.
                    device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
                    device.executeShellCommand("wm dismiss-keyguard")
                }
            }
        BudgetGate.log("T-PERF-09 screen off ${SCREEN_OFF_MS / 1000} s: cpuTicks=$cpu")
        assertTrue("T-PERF-09: $cpu CPU ticks with the screen off", cpu <= MAX_SCREEN_OFF_CPU_TICKS)
    }

    internal companion object {
        private const val PER_USER_RANGE = 100_000
        private const val QUIET_SAMPLE_MS = 1_000L
        private const val QUIET_SAMPLES = 3
        private const val QUIET_TICKS = 2L
        private const val QUIET_TIMEOUT_MS = 30_000L
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

        /** The pids printed by `pidof` (space-separated; empty when the process is not running). */
        fun pidsOf(output: String): List<String> = output.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }

        /** Whether `dumpsys activity activities` names [component] as the resumed activity. */
        fun resumedIs(
            dump: String,
            component: String,
        ): Boolean =
            dump.lines().any { line ->
                (line.contains("mResumedActivity") || line.contains("ResumedActivity:")) && line.contains(component)
            }

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
