package app.strata.launcher.settings

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.strata.launcher.DEVICE_TIMEOUT_MS
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.appGraph
import app.strata.launcher.core.model.OnboardingProgress
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
 * reaches a focused window of the expected screen quickly, the entry itself never holds focus, and no ANR is recorded
 * for Strata. Every shell command names the numeric user.
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
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED)
                .flatMap { monitor.getActivitiesInStage(it) }
                .filter { it is SettingsActivity }
                .forEach(Activity::finish)
        }
    }

    private fun setOnboarded(done: Boolean) =
        runBlocking {
            graph.settingsRepository.update {
                it.copy(onboardingDone = done, onboardingProgress = OnboardingProgress.WELCOME)
            }
        }

    /** Lines of the events log that record an ANR of Strata (`am_anr`). */
    private fun anrCount(): Int =
        shell("logcat -d -b events -s am_anr").lineSequence().count { it.contains(pkg) }

    private fun focusedWindow(): String =
        shell(
            "dumpsys window",
        ).lineSequence().map(String::trim).firstOrNull { it.startsWith("mCurrentFocus") }.orEmpty()

    private fun startEntry(): Long {
        val started = SystemClock.uptimeMillis()
        val out =
            shell(
                "am start --user $userId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                    "-n $pkg/.settings.EntryActivity",
            )
        assertWithMessage("am start refused: $out").that(out).doesNotContain("Error")
        return started
    }

    /**
     * Waits for a focused window whose name contains [activity]; asserts it arrived within [FOCUS_BUDGET_MS] (below
     * the system's 5 s wait for a focused window) and that the entry never held focus meanwhile.
     */
    private fun assertFocusReached(
        activity: String,
        started: Long,
    ) {
        var entryFocused = false
        val reached =
            pollUntil(DEVICE_TIMEOUT_MS) {
                val focus = focusedWindow()
                if (focus.contains("EntryActivity")) entryFocused = true
                focus.contains(activity)
            }
        val elapsed = SystemClock.uptimeMillis() - started
        assertWithMessage("$activity never got a focused window. ${focusReport()}").that(reached).isTrue()
        assertWithMessage("the window-less entry held focus").that(entryFocused).isFalse()
        assertWithMessage("focus on $activity took $elapsed ms").that(elapsed).isLessThan(FOCUS_BUDGET_MS)
    }

    /** T-HOME-17: onboarded: the entry opens home with a focused window, finishes, and no ANR is recorded. */
    @Test
    fun tHome17_onboardedEntryOpensHomeQuickly() {
        setOnboarded(true)
        val anrs = anrCount()
        val started = startEntry()
        assertFocusReached("HomeActivity", started)
        // The system's own wait for a focused window would end here; nothing may be recorded for Strata.
        SystemClock.sleep(ANR_WINDOW_MS)
        assertWithMessage("an ANR was recorded for Strata").that(anrCount()).isEqualTo(anrs)
        assertWithMessage("focus left home. ${focusReport()}").that(focusedWindow()).contains("HomeActivity")
    }

    /** T-HOME-17: not onboarded: the entry hands over to settings, which shows onboarding in a focused window. */
    @Test
    fun tHome17_firstRunEntryShowsOnboarding() {
        setOnboarded(false)
        val anrs = anrCount()
        val started = startEntry()
        assertFocusReached("SettingsActivity", started)
        assertWithMessage("onboarding not shown. ${focusReport()}")
            .that(device.wait(Until.hasObject(By.res(OnboardingTestTags.ROOT)), DEVICE_TIMEOUT_MS))
            .isTrue()
        SystemClock.sleep(ANR_WINDOW_MS)
        assertWithMessage("an ANR was recorded for Strata").that(anrCount()).isEqualTo(anrs)
    }

    private companion object {
        /** Below the system's 5 s wait for a focused window after a launch. */
        const val FOCUS_BUDGET_MS = DEVICE_TIMEOUT_MS / 3

        /** As long as that system wait: an ANR caused by the launch would have been recorded by then. */
        const val ANR_WINDOW_MS = DEVICE_TIMEOUT_MS / 3
    }
}
