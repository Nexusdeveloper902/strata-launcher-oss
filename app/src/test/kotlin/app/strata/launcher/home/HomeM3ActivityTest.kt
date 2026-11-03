package app.strata.launcher.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.AppSection
import app.strata.launcher.core.model.IndexLetter
import app.strata.launcher.core.model.Settings
import app.strata.launcher.feature.home.HomeAction
import app.strata.launcher.feature.home.HomeModal
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.HomeViewModel
import app.strata.launcher.feature.home.SheetTestTags
import app.strata.launcher.feature.search.SearchTestTags
import app.strata.launcher.testing.fakes.FakeAppListSource
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fakes.FakeSystemIntents
import app.strata.launcher.testing.fixtures.SearchFixtures.visible
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** HomeActivity with the M3 home (Robolectric, levels R and C). Device halves are in androidTest. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HomeM3ActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val maps = visible("Maps")
    private val music = visible("Music")
    private val list =
        AppList(
            sections = listOf(AppSection("M", listOf(maps, music))),
            index = listOf(IndexLetter("M", 0, 2)),
            hiddenCount = 0,
            fromSnapshot = false,
        )

    private lateinit var controller: ActivityController<HomeActivity>

    private fun start(settings: Settings = Settings(onboardingDone = true)): Pair<HomeActivity, TestAppGraph> {
        val g =
            TestAppGraph(
                roleController = FakeRoleController(defaultHome = true),
                settingsRepository = FakeSettingsRepository(settings),
                appList = FakeAppListSource(list),
            ).install()
        controller = Robolectric.buildActivity(HomeActivity::class.java, homeIntent()).setup()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        return controller.get() to g
    }

    private fun homeIntent() =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setComponent(ComponentName(context, HomeActivity::class.java))

    private fun HomeActivity.vm(): HomeViewModel =
        androidx.lifecycle
            .ViewModelProvider(this)
            .get(HomeViewModel::class.java)

    /** T-HOME-06: no clock handler → toast "No app can open this", no crash. */
    @Test
    fun tHome06_noHandlerToast() {
        val (_, g) = start()
        g.systemIntents.missing += FakeSystemIntents.ALARMS
        compose.onNodeWithTag(HomeTestTags.CLOCK).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("No app can open this")
    }

    /** T-HOME-11 (R half): on a phone, rotation is locked to portrait unless "Allow rotation" is on. */
    @Test
    fun tHome11_phoneRotationLock() {
        val (activity, g) = start()
        assertThat(activity.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        g.settingsRepository.set(Settings(onboardingDone = true, allowRotation = true))
        compose.waitForIdle()
        assertThat(activity.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }

    /** T-HOME-12 (R half): at sw ≥ 600 dp home follows the orientation even with the setting off. */
    @Test
    @Config(qualifiers = "sw800dp-w800dp-h1280dp")
    fun tHome12_tabletAlwaysRotates() {
        val (activity, _) = start()
        assertThat(activity.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        assertThat(
            HomeWindow.orientation(599, allowRotation = false),
        ).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
    }

    /** T-APPS-05 (search half): long-press on a search result opens the same sheet over search. */
    @Test
    fun tApps05_sheetFromSearchResult() {
        val (activity, _) = start()
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_M))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_M))
        compose.waitForIdle()
        compose.onNodeWithTag(SearchTestTags.row(maps)).performTouchInput { longClick() }
        compose.waitForIdle()
        assertThat(
            activity
                .vm()
                .state.value.modal,
        ).isEqualTo(HomeModal.Sheet(maps.key))
        compose.onNodeWithTag(SheetTestTags.SHEET).assertExists()
        compose.onNodeWithTag(SearchTestTags.LAYER).assertExists()
    }

    /** T-LIFE-03 (R half): rotation, dark mode and font scale keep the open sheet and the activity instance. */
    @Test
    fun tLife03_configChangesKeepState() {
        val (activity, _) = start()
        activity.vm().onAction(HomeAction.OpenSheet(maps))
        compose.waitForIdle()
        val config = Configuration(activity.resources.configuration)
        config.orientation = Configuration.ORIENTATION_LANDSCAPE
        config.uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL
        config.fontScale = 1.3f
        controller.configurationChange(config)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        assertThat(controller.get()).isSameInstanceAs(activity)
        assertThat(
            activity
                .vm()
                .state.value.modal,
        ).isEqualTo(HomeModal.Sheet(maps.key))
        compose.onNodeWithTag(SheetTestTags.SHEET).assertExists()
    }

    /** FR-GEST-03: with the service off, the lock hint's action opens the system accessibility settings. */
    @Test
    fun lockHintOpensAccessibilitySettings() {
        val (activity, g) = start(Settings(onboardingDone = true, lockGestureEnabled = true))
        activity.vm().onAction(HomeAction.LockScreen)
        compose.waitForIdle()
        compose.onNodeWithText("Turn on screen lock in settings").assertExists()
        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        assertThat(g.lockService.locks).isEqualTo(0)
        assertThat(g.systemIntents.started).containsExactly(FakeSystemIntents.ACCESSIBILITY)
    }

    /** INTERACTION_SPEC §6: Home while the sheet is open closes it (one Home press → HOME_REST). */
    @Test
    fun homePressClosesSheet() {
        val (activity, _) = start()
        activity.vm().onAction(HomeAction.OpenSheet(maps))
        compose.waitForIdle()
        controller.newIntent(homeIntent())
        compose.waitForIdle()
        assertThat(
            activity
                .vm()
                .state.value.modal,
        ).isEqualTo(HomeModal.None)
    }
}
