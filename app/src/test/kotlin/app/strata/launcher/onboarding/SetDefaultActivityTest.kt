package app.strata.launcher.onboarding

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import app.strata.launcher.core.platformapi.system.DeviceBuild
import app.strata.launcher.core.platformapi.system.SystemIntent
import app.strata.launcher.feature.onboarding.OnboardingTestTags
import app.strata.launcher.feature.settings.SettingsTestTags
import app.strata.launcher.settings.SettingsActivity
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSystemIntents
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * The set-default flow through the real activities and their result launcher (Robolectric, level R): the system
 * screens are started for a result and their return is checked against [FakeRoleController].
 */
@RunWith(AndroidJUnit4::class)
class SetDefaultActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun startOnboarding(): SettingsActivity {
        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(context, SettingsActivity::class.java))
        val activity = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().get()
        idle()
        compose.onNodeWithTag(OnboardingTestTags.CONTINUE).performClick()
        compose.onNodeWithTag(OnboardingTestTags.SKIP).performClick()
        idle()
        return activity
    }

    /** Delivers [resultCode] for the screen the activity started for a result last; returns that screen's intent. */
    private fun returnFrom(
        activity: Activity,
        resultCode: Int,
    ): Intent {
        val started = shadowOf(activity).nextStartedActivityForResult
        assertThat(started).isNotNull()
        shadowOf(activity).receiveResult(started.intent, resultCode, null)
        idle()
        return started.intent
    }

    /**
     * T-ONBOARD-07 (R): the fake returns RESULT_OK but the role is not held → OEM help card once with Retry; then the
     * settings path returns without a change → the card again for that attempt, with the HyperOS note for a HyperOS 2
     * build.
     */
    @Test
    fun tOnboard07_oemHelpAfterOkWithoutRole_andSettingsWithoutChange() {
        val role = FakeRoleController(defaultHome = false, request = SystemIntent.HomeSettings)
        TestAppGraph(roleController = role, onboarded = false).install()
        val activity = startOnboarding()

        compose.onNodeWithTag(OnboardingTestTags.SET_DEFAULT).performClick()
        idle()
        val first = returnFrom(activity, Activity.RESULT_OK)
        assertThat(first.action).isEqualTo(Settings.ACTION_HOME_SETTINGS)
        compose.onNodeWithTag(OnboardingTestTags.OEM_HELP).assertExists()
        compose.onNodeWithTag(OnboardingTestTags.OEM_BLOCKING_NOTE).assertDoesNotExist()

        role.device = HYPER_OS_2
        compose.onNodeWithTag(OnboardingTestTags.RETRY).performClick()
        idle()
        compose.onNodeWithTag(OnboardingTestTags.OEM_HELP).assertDoesNotExist() // one card per attempt
        returnFrom(activity, Activity.RESULT_CANCELED)
        compose.onNodeWithTag(OnboardingTestTags.OEM_HELP).assertExists()
        compose.onNodeWithTag(OnboardingTestTags.OEM_BLOCKING_NOTE).assertExists()

        // The user picked Strata in the system settings this time: the flow moves on to Done.
        compose.onNodeWithTag(OnboardingTestTags.RETRY).performClick()
        idle()
        role.defaultHome = true
        returnFrom(activity, Activity.RESULT_CANCELED)
        compose.onNodeWithTag(OnboardingTestTags.DONE).assertExists()
    }

    /**
     * T-SET-03 (R): with Strata as home the row reads "Strata is your home app"; "Uninstall Strata" explains the switch
     * first and opens the home settings; after the switch, the system uninstall dialog opens.
     */
    @Test
    fun tSet03_defaultHomeRow_andUninstall() {
        val role = FakeRoleController(defaultHome = true)
        val graph = TestAppGraph(roleController = role, onboarded = true).install()
        val intent =
            Intent(Intent.ACTION_APPLICATION_PREFERENCES)
                .setComponent(ComponentName(context, SettingsActivity::class.java))
        val activity = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().get()
        idle()
        compose.onNodeWithTag(SettingsTestTags.DEFAULT_HOME).assertExists()
        compose.onNodeWithText("Strata is your home app").assertExists()

        compose.onNodeWithTag(SettingsTestTags.UNINSTALL_STRATA).performClick()
        idle()
        compose.onNodeWithTag(SettingsTestTags.UNINSTALL_GUIDANCE).assertExists()
        assertThat(graph.systemIntents.started).doesNotContain(FakeSystemIntents.UNINSTALL_STRATA)
        compose.onNodeWithTag(SettingsTestTags.UNINSTALL_GUIDANCE_CONTINUE).performClick()
        idle()
        role.defaultHome = false // the user picked another home app
        val opened = returnFrom(activity, Activity.RESULT_CANCELED)
        assertThat(opened.action).isEqualTo(Settings.ACTION_HOME_SETTINGS)
        assertThat(graph.systemIntents.started).containsExactly(FakeSystemIntents.UNINSTALL_STRATA)
    }

    /** FR-SET-02: while Strata is not home, "Uninstall Strata" opens the system dialog directly. */
    @Test
    fun uninstallWhenNotHome_opensDialogDirectly() {
        val graph = TestAppGraph(roleController = FakeRoleController(defaultHome = false), onboarded = true).install()
        val intent =
            Intent(Intent.ACTION_APPLICATION_PREFERENCES)
                .setComponent(ComponentName(context, SettingsActivity::class.java))
        Robolectric.buildActivity(SettingsActivity::class.java, intent).setup()
        idle()
        compose.onNodeWithTag(SettingsTestTags.UNINSTALL_STRATA).performClick()
        idle()
        compose.onNodeWithTag(SettingsTestTags.UNINSTALL_GUIDANCE).assertDoesNotExist()
        assertThat(graph.systemIntents.started).containsExactly(FakeSystemIntents.UNINSTALL_STRATA)
    }
}

private val HYPER_OS_2 =
    DeviceBuild(
        manufacturer = "Xiaomi",
        brand = "Redmi",
        fingerprint = "Redmi/x/x:15/AQ3A.240912.001/OS2.0.2.0.VNOMIXM:user/release-keys",
        incremental = "OS2.0.2.0.VNOMIXM",
    )
