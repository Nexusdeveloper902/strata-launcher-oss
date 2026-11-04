package app.strata.launcher.onboarding

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.appGraph
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.onboarding.OnboardingTestTags
import app.strata.launcher.settings.SettingsActivity
import app.strata.launcher.shell
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * First run on a device (FR-ONBOARD-01…03, level I). Driven with UiAutomator over the compose test tags (exposed as
 * resource ids), so no compose rule owns the activities: the flow crosses from the entry activity to HomeActivity in
 * another task. Every test restores `onboarding_done` and the banner snooze in [restore].
 *
 * Tests that need a human-driven system dialog (role dialog accept/deny, the settings home picker) run only with the
 * instrumentation argument `strata.manual=true` (the owner's Galaxy A56 pass); on CI they are skipped.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingDeviceTest {
    @get:Rule
    val deviceState = DeviceStateRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context: Context = instrumentation.targetContext
    private val graph get() = context.applicationContext.appGraph

    @Before
    fun firstRun() =
        runBlocking {
            graph.settingsRepository.update { it.copy(onboardingDone = false, defaultHomeBannerDismissedUntil = 0L) }
        }

    @After
    fun restore() =
        runBlocking {
            graph.settingsRepository.update { it.copy(onboardingDone = true, defaultHomeBannerDismissedUntil = 0L) }
        }

    private fun tag(t: String): BySelector = By.res(t)

    private fun waitFor(
        t: String,
        timeout: Long = 10_000,
    ): Boolean = device.wait(Until.hasObject(tag(t)), timeout)

    private fun stepText(): String? = device.findObject(tag(OnboardingTestTags.STEP))?.text

    /** Clicks [t] and waits until the step changed or the flow is gone, so the next look sees the next screen. */
    private fun click(t: String) {
        assertThat(waitFor(t)).isTrue()
        val before = stepText()
        device.findObject(tag(t)).click()
        val until = System.currentTimeMillis() + STEP_TIMEOUT_MS
        while (stepText() == before && System.currentTimeMillis() < until) Thread.sleep(POLL_MS)
        device.waitForIdle()
    }

    /** A runtime-permission dialog of the system permission controller (T-ONBOARD-01 watcher). */
    private fun permissionDialogShown(): Boolean =
        device.hasObject(By.pkg(Pattern.compile(".*permissioncontroller.*"))) ||
            device.hasObject(By.res(Pattern.compile(".*:id/grant_dialog")))

    private fun startFromLauncherIcon() {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(context, SettingsActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    private fun onboardingDone(): Boolean =
        runBlocking {
            graph.settingsRepository.settings
                .first()
                .onboardingDone
        }

    private fun manual(): Boolean = InstrumentationRegistry.getArguments().getString("strata.manual") == "true"

    /**
     * T-ONBOARD-01: fresh first run from the launcher icon, completed without changes: ≤ 4 screens, no runtime
     * permission dialog at any point, ends on home with `onboarding_done` set.
     */
    @Test
    fun tOnboard01_firstRunWithoutChanges() {
        startFromLauncherIcon()
        assertThat(waitFor(OnboardingTestTags.WELCOME)).isTrue()
        var screens = 0
        while (!onboardingDone() && screens < 6) {
            assertThat(permissionDialogShown()).isFalse()
            screens++
            when {
                device.hasObject(tag(OnboardingTestTags.WELCOME)) -> {
                    click(OnboardingTestTags.CONTINUE)
                }

                device.hasObject(tag(OnboardingTestTags.SKIP)) -> {
                    click(OnboardingTestTags.SKIP)
                }

                else -> {
                    // The set-default step: Continue while Strata is home (DeviceStateRule), else "Not now"; then Done.
                    device.wait(
                        Until.hasObject(
                            By.res(Pattern.compile("${OnboardingTestTags.CONTINUE}|${OnboardingTestTags.NOT_NOW}")),
                        ),
                        10_000,
                    )
                    click(
                        if (device.hasObject(tag(OnboardingTestTags.NOT_NOW))) {
                            OnboardingTestTags.NOT_NOW
                        } else {
                            OnboardingTestTags.CONTINUE
                        },
                    )
                }
            }
        }
        assertThat(onboardingDone()).isTrue()
        assertThat(screens).isAtMost(4)
        assertThat(waitFor(HomeTestTags.ROOT, 15_000)).isTrue()
        assertThat(permissionDialogShown()).isFalse()
    }

    /**
     * T-ONBOARD-09: never opened; Strata becomes the home app from the system side, then Home is pressed. HomeActivity
     * shows onboarding as its first layer; the set-default step says "Strata is your home app" with no action; Done →
     * home with `onboarding_done` = true.
     */
    @Test
    fun tOnboard09_firstRunStartingAtHome() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            shell("cmd role add-role-holder android.app.role.HOME ${context.packageName}")
        }
        assumeTrue(graph.roleController.isDefaultHome())
        device.pressHome()
        assertThat(waitFor(OnboardingTestTags.WELCOME, 15_000)).isTrue()
        click(OnboardingTestTags.CONTINUE)
        click(OnboardingTestTags.SKIP)
        assertThat(waitFor(OnboardingTestTags.HELD)).isTrue()
        assertThat(device.hasObject(tag(OnboardingTestTags.SET_DEFAULT))).isFalse()
        click(OnboardingTestTags.CONTINUE)
        assertThat(waitFor(OnboardingTestTags.DONE)).isTrue()
        click(OnboardingTestTags.CONTINUE)
        assertThat(device.wait(Until.gone(tag(OnboardingTestTags.ROOT)), 10_000)).isTrue()
        assertThat(onboardingDone()).isTrue()
        assertThat(waitFor(HomeTestTags.ROOT)).isTrue()
    }

    /** T-ONBOARD-03 (manual): accept the role dialog → RESULT_OK and held → Done → home. */
    @Test
    fun tOnboard03_roleGranted_manual() {
        assumeTrue(manual() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        releaseHomeRole()
        startFromLauncherIcon()
        click(OnboardingTestTags.CONTINUE)
        click(OnboardingTestTags.SKIP)
        click(OnboardingTestTags.SET_DEFAULT)
        // The human picks Strata in the system role dialog.
        assertThat(waitFor(OnboardingTestTags.DONE, 60_000)).isTrue()
        assertThat(graph.roleController.isDefaultHome()).isTrue()
        click(OnboardingTestTags.CONTINUE)
        assertThat(waitFor(HomeTestTags.ROOT)).isTrue()
    }

    /** T-ONBOARD-04 (API 26–28): "Set as default" opens HOME_SETTINGS; the return is checked with `resolveActivity`. */
    @Test
    fun tOnboard04_api26to28_homeSettings_manual() {
        assumeTrue(manual() && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
        releaseHomeRole()
        startFromLauncherIcon()
        click(OnboardingTestTags.CONTINUE)
        click(OnboardingTestTags.SKIP)
        click(OnboardingTestTags.SET_DEFAULT)
        assertThat(device.wait(Until.hasObject(By.pkg("com.android.settings")), 10_000)).isTrue()
        // The human picks Strata (or nothing) and presses Back: Done when held, else the OEM help card.
        assertThat(
            device.wait(
                Until.hasObject(By.res(Pattern.compile("${OnboardingTestTags.DONE}|${OnboardingTestTags.OEM_HELP}"))),
                60_000,
            ),
        ).isTrue()
        assertThat(device.hasObject(tag(OnboardingTestTags.DONE))).isEqualTo(graph.roleController.isDefaultHome())
    }

    /**
     * T-ONBOARD-05 (manual): with "don't ask again" ticked after two denials, "Try again" opens the home settings
     * directly; no silent loop.
     */
    @Test
    fun tOnboard05_dontAskAgain_manual() {
        assumeTrue(manual() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        releaseHomeRole()
        startFromLauncherIcon()
        click(OnboardingTestTags.CONTINUE)
        click(OnboardingTestTags.SKIP)
        click(OnboardingTestTags.SET_DEFAULT)
        // The human denies twice, ticking "don't ask again" the second time.
        assertThat(waitFor(OnboardingTestTags.DENIED_CARD, 60_000)).isTrue()
        click(OnboardingTestTags.TRY_AGAIN)
        assertThat(waitFor(OnboardingTestTags.DENIED_CARD, 60_000)).isTrue()
        click(OnboardingTestTags.TRY_AGAIN)
        assertThat(device.wait(Until.hasObject(By.pkg("com.android.settings")), 10_000)).isTrue()
    }

    private companion object {
        const val STEP_TIMEOUT_MS = 5_000L
        const val POLL_MS = 100L
    }

    /** Gives the home role back to another home app, so the role dialog can be shown (manual tests only). */
    private fun releaseHomeRole() {
        val other =
            context.packageManager
                .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .map { it.activityInfo.packageName }
                .firstOrNull { it != context.packageName && it != "com.android.settings" }
        assumeTrue(other != null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            shell("cmd role add-role-holder android.app.role.HOME $other")
        } else {
            context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
