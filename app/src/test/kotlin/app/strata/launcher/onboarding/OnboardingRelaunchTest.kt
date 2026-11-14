package app.strata.launcher.onboarding

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import app.strata.launcher.core.model.Settings
import app.strata.launcher.feature.onboarding.OnboardingTestTags
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.settings.SettingsActivity
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Owner report (Galaxy A56): "When you set Strata as default it resets the setup process". Granting the home role makes
 * the system start the HOME activity, a second host of the flow (D-008) with its own view model and, because of
 * `stateNotNeeded`, no saved state; the process may also be killed while the role screen is open. None of these may
 * send the user back to Welcome (FR-ONBOARD-01/02, J-01 step 4; T-ONBOARD-03 and T-ONBOARD-09, level R). The fake
 * settings store stands in for `settings.pb`, which outlives the activities and the process.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingRelaunchTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun entryIntent() =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(context, SettingsActivity::class.java))

    private fun homeIntent() =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setComponent(ComponentName(context, HomeActivity::class.java))

    private fun startEntry(): ActivityController<SettingsActivity> =
        Robolectric.buildActivity(SettingsActivity::class.java, entryIntent()).setup().also { idle() }

    private fun startHome(): ActivityController<HomeActivity> =
        Robolectric.buildActivity(HomeActivity::class.java, homeIntent()).setup().also { idle() }

    /** Process death: the activity goes away with its view models, and the new one gets no saved state. */
    private fun kill(controller: ActivityController<*>) {
        controller.pause().stop().destroy()
        idle()
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        idle()
    }

    private fun graph(role: FakeRoleController): FakeSettingsRepository {
        val store = FakeSettingsRepository(Settings(onboardingDone = false))
        TestAppGraph(roleController = role, settingsRepository = store).install()
        return store
    }

    /**
     * The reported path: the flow runs in the entry activity, "Set as default" opens the role screen, the user accepts,
     * and the system starts HomeActivity. Home's layer continues at Done, not at Welcome; Done ends the flow.
     */
    @Test
    fun roleGrantedFromEntry_homeStartedBySystem_continuesAtDone() {
        val role = FakeRoleController(defaultHome = false)
        val store = graph(role)
        val entry = startEntry()
        click(OnboardingTestTags.CONTINUE) // Welcome
        click(OnboardingTestTags.SKIP) // Pick favourites
        click(OnboardingTestTags.SET_DEFAULT)
        assertThat(shadowOf(entry.get()).peekNextStartedActivityForResult()).isNotNull()

        role.defaultHome = true // the user accepted the role dialog
        kill(entry) // the entry task goes to the background (or the process is killed) …
        startHome() // … and the system starts the new home activity

        compose.onNodeWithTag(OnboardingTestTags.WELCOME).assertDoesNotExist()
        compose.onNodeWithTag(OnboardingTestTags.DONE).assertExists()
        click(OnboardingTestTags.CONTINUE)
        assertThat(store.value.onboardingDone).isTrue()
        compose.onNodeWithTag(OnboardingTestTags.ROOT).assertDoesNotExist()
    }

    /** The same grant while home itself hosts the flow and the process dies before the result arrives. */
    @Test
    fun roleGrantedFromHome_afterProcessDeath_continuesAtDone() {
        val role = FakeRoleController(defaultHome = false)
        graph(role)
        val home = startHome()
        click(OnboardingTestTags.CONTINUE)
        click(OnboardingTestTags.SKIP)
        click(OnboardingTestTags.SET_DEFAULT)
        role.defaultHome = true
        kill(home)
        startHome()
        compose.onNodeWithTag(OnboardingTestTags.WELCOME).assertDoesNotExist()
        compose.onNodeWithTag(OnboardingTestTags.DONE).assertExists()
    }

    /** Process death of the entry activity in the middle of the flow keeps the step. */
    @Test
    fun entryProcessDeath_keepsStep() {
        graph(FakeRoleController(defaultHome = false))
        val entry = startEntry()
        click(OnboardingTestTags.CONTINUE)
        compose.onNodeWithTag(OnboardingTestTags.SKIP).assertExists()
        kill(entry)
        startEntry()
        compose.onNodeWithTag(OnboardingTestTags.WELCOME).assertDoesNotExist()
        compose.onNodeWithTag(OnboardingTestTags.SKIP).assertExists()
    }

    /** Home press (`onNewIntent` MAIN+HOME), recreation and process death of home keep the step. */
    @Test
    fun homeIntent_recreate_andProcessDeath_keepStep() {
        graph(FakeRoleController(defaultHome = false))
        val home = startHome()
        click(OnboardingTestTags.CONTINUE)
        click(OnboardingTestTags.SKIP)
        compose.onNodeWithTag(OnboardingTestTags.SET_DEFAULT_STEP).assertExists()

        home.newIntent(homeIntent())
        idle()
        compose.onNodeWithTag(OnboardingTestTags.SET_DEFAULT_STEP).assertExists()

        home.recreate()
        idle()
        compose.onNodeWithTag(OnboardingTestTags.SET_DEFAULT_STEP).assertExists()

        kill(home)
        startHome()
        compose.onNodeWithTag(OnboardingTestTags.WELCOME).assertDoesNotExist()
        compose.onNodeWithTag(OnboardingTestTags.SET_DEFAULT_STEP).assertExists()
    }

    /** Back from Done to the held set-default step stays there (the stored mark does not bounce it to Done). */
    @Test
    fun backFromDone_staysOnSetDefault() {
        val role = FakeRoleController(defaultHome = false)
        graph(role)
        val home = startHome()
        click(OnboardingTestTags.CONTINUE)
        click(OnboardingTestTags.SKIP)
        click(OnboardingTestTags.SET_DEFAULT)
        role.defaultHome = true
        kill(home)
        val again = startHome()
        compose.onNodeWithTag(OnboardingTestTags.DONE).assertExists()
        again.get().onBackPressedDispatcher.onBackPressed()
        idle()
        again.pause().resume()
        idle()
        compose.onNodeWithTag(OnboardingTestTags.HELD).assertExists()
        compose.onNodeWithTag(OnboardingTestTags.DONE).assertDoesNotExist()
    }
}
