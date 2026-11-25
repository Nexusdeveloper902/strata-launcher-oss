package app.strata.launcher.settings

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.strata.launcher.DEVICE_TIMEOUT_MS
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.appGraph
import app.strata.launcher.core.model.OnboardingProgress
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.onboarding.OnboardingTestTags
import app.strata.launcher.focusReport
import app.strata.launcher.pollUntil
import app.strata.launcher.shell
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-HOME-17 (FB-019, level I): the app icon's entry (`EntryActivity`, `MAIN` + `LAUNCHER`) started with `am start`
 * reaches a focused window of home (onboarding as its first layer on a first run) quickly, the entry itself never holds
 * focus, it is destroyed (nothing of it is left that could make the system wait), and no ANR is recorded for Strata
 * since the start. Runs with a warm process (the instrumentation lives in Strata's process); the cold path is the same
 * code, since the entry reads nothing. Every shell command names the numeric user.
 */
@RunWith(AndroidJUnit4::class)
class EntryDeviceTest {
    @get:Rule
    val deviceState = DeviceStateRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context: Context = instrumentation.targetContext
    private val pkg = context.packageName
    private val graph get() = context.applicationContext.appGraph

    /** Numeric user of this test (never `current`). */
    private val userId = android.os.Process.myUid() / 100_000

    @After
    fun restore() {
        runBlocking {
            graph.settingsRepository.update {
                it.copy(onboardingDone = true, onboardingProgress = OnboardingProgress.WELCOME)
            }
        }
    }

    private fun setOnboarded(done: Boolean) =
        runBlocking {
            graph.settingsRepository.update {
                it.copy(onboardingDone = done, onboardingProgress = OnboardingProgress.WELCOME)
            }
        }

    /** Events-log lines (activity manager / window manager tags) since [sinceSeconds] (device wall clock). */
    private fun events(sinceSeconds: String): List<String> =
        shell("logcat -d -b events -T $sinceSeconds").lines()

    private fun focusedWindow(): String =
        shell(
            "dumpsys window",
        ).lineSequence().map(String::trim).firstOrNull { it.startsWith("mCurrentFocus") }.orEmpty()

    /** Starts the entry; returns the events-log marker taken just before and the uptime when `am start` returned. */
    private fun startEntry(): Pair<String, Long> {
        val marker = "%.3f".format(java.util.Locale.ROOT, System.currentTimeMillis() / 1000.0)
        val out =
            shell(
                "am start --user $userId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                    "-n $pkg/.settings.EntryActivity",
            )
        val returned = SystemClock.uptimeMillis()
        assertWithMessage("am start refused: $out").that(out).doesNotContain("Error")
        return marker to returned
    }

    /**
     * After `am start` returned ([returned]): home's window is focused within [FOCUS_BUDGET_MS] (well below the
     * system's 5 s wait for a focused window) and the entry never held focus. Then, as the positive signal that the
     * launch is over, the events log shows the entry destroyed (within [SETTLE_BUDGET_MS]); no `am_anr` line for Strata
     * may appear since [marker].
     */
    private fun assertCleanLaunch(
        marker: String,
        returned: Long,
    ) {
        var entryFocused = false
        val reached =
            pollUntil(FOCUS_BUDGET_MS) {
                val focus = focusedWindow()
                if (focus.contains("EntryActivity")) entryFocused = true
                focus.contains("HomeActivity")
            }
        val elapsed = SystemClock.uptimeMillis() - returned
        assertWithMessage("home never got a focused window in $elapsed ms. ${focusReport()}").that(reached).isTrue()
        assertWithMessage("the window-less entry held focus").that(entryFocused).isFalse()
        val destroyed =
            pollUntil(SETTLE_BUDGET_MS) {
                events(marker).any { DESTROY.containsMatchIn(it) && it.contains("EntryActivity") }
            }
        val log = events(marker)
        assertWithMessage("the entry was not destroyed. ${log.takeLast(20)}").that(destroyed).isTrue()
        val anrs = log.filter { ANR.containsMatchIn(it) && it.contains(pkg) }
        assertWithMessage("ANR recorded for Strata").that(anrs).isEmpty()
    }

    /** T-HOME-17: onboarded: the entry opens home with a focused window and no ANR. */
    @Test
    fun tHome17_onboardedEntryOpensHomeQuickly() {
        setOnboarded(true)
        val (marker, returned) = startEntry()
        assertCleanLaunch(marker, returned)
        assertWithMessage("home not shown. ${focusReport()}")
            .that(device.wait(Until.hasObject(By.res(HomeTestTags.ROOT)), DEVICE_TIMEOUT_MS))
            .isTrue()
    }

    /** T-HOME-17: first run: home shows onboarding as its first layer, focused, with no ANR. */
    @Test
    fun tHome17_firstRunEntryShowsOnboardingInHome() {
        setOnboarded(false)
        val (marker, returned) = startEntry()
        assertCleanLaunch(marker, returned)
        assertWithMessage("onboarding not shown. ${focusReport()}")
            .that(device.wait(Until.hasObject(By.res(OnboardingTestTags.WELCOME)), DEVICE_TIMEOUT_MS))
            .isTrue()
    }

    private companion object {
        /** Well below the system's 5 s wait for a focused window after a launch. */
        const val FOCUS_BUDGET_MS = DEVICE_TIMEOUT_MS / 4

        /** Until the entry's destruction is logged (a positive signal, normally far sooner). */
        const val SETTLE_BUDGET_MS = DEVICE_TIMEOUT_MS

        /** `am_destroy_activity` (API < 29) or `wm_destroy_activity`. */
        val DESTROY = Regex("""\b(am|wm)_destroy_activity\b""")
        val ANR = Regex("""\bam_anr\b""")
    }
}
