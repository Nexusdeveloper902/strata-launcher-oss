package app.strata.launcher.settings

import android.app.Activity
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.feature.settings.SettingsPageTags
import app.strata.launcher.feature.settings.SettingsTestTags
import app.strata.launcher.focusReport
import app.strata.launcher.shell
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings entry points from outside Strata (level I): T-SET-01 (the system App info gear = `APPLICATION_PREFERENCES`)
 * and T-SEC-04 (`VIEW_PERMISSION_USAGE` from the shell is refused: only the system holds
 * `START_VIEW_PERMISSION_USAGE`). The system privacy dashboard's own link (T-SET-04 device half) is a manual check.
 */
@RunWith(AndroidJUnit4::class)
class SettingsDeviceTest {
    @get:Rule
    val deviceState = DeviceStateRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = instrumentation.targetContext.packageName

    private val liveStages = listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED)

    private fun live(type: Class<out Activity>): List<Activity> {
        val found = mutableListOf<Activity>()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            liveStages.flatMapTo(found) { stage -> monitor.getActivitiesInStage(stage).filter(type::isInstance) }
        }
        return found
    }

    private fun finishSettings() {
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            liveStages
                .flatMap { monitor.getActivitiesInStage(it) }
                .filter { it is SettingsActivity || it is PermissionUsageActivity }
                .forEach { it.finish() }
        }
        val end = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < end &&
            (live(SettingsActivity::class.java).isNotEmpty() || live(PermissionUsageActivity::class.java).isNotEmpty())
        ) {
            SystemClock.sleep(100)
        }
    }

    @Before
    fun before() = finishSettings()

    @After
    fun after() = finishSettings()

    /** T-SET-01 (I): what the system App info gear sends opens the settings root (no routing). */
    @Test
    fun tSet01_applicationPreferencesOpensSettings() {
        shell("am start -a android.intent.action.APPLICATION_PREFERENCES -p $pkg")
        assertWithMessage("settings root not shown. ${focusReport()}")
            .that(device.wait(Until.hasObject(By.res(SettingsTestTags.ROOT)), 15_000))
            .isTrue()
    }

    /**
     * T-SEC-04: the shell cannot start PermissionUsageActivity. Positive control first: the same `am start` reaches
     * an unguarded exported activity (the settings router), so a refused start is the permission guard, not the shell.
     */
    @Test
    fun tSec04_permissionUsageGuard() {
        shell("am start -n $pkg/.settings.SettingsActivity -a android.intent.action.APPLICATION_PREFERENCES")
        assertWithMessage("positive control: settings not shown. ${focusReport()}")
            .that(device.wait(Until.hasObject(By.res(SettingsTestTags.ROOT)), 15_000))
            .isTrue()
        finishSettings()

        // stdout of `am start` may be empty (errors go to stderr, which executeShellCommand drops); the activity
        // monitor and the window are the evidence.
        shell("am start -n $pkg/.settings.PermissionUsageActivity -a android.intent.action.VIEW_PERMISSION_USAGE")
        val end = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < end) {
            assertWithMessage("PermissionUsageActivity started from the shell")
                .that(live(PermissionUsageActivity::class.java))
                .isEmpty()
            assertWithMessage("the privacy page opened from the shell")
                .that(device.hasObject(By.res(SettingsPageTags.PRIVACY_PAGE)))
                .isFalse()
            SystemClock.sleep(250)
        }
    }
}
