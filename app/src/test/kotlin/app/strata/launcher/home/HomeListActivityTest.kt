package app.strata.launcher.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.AppSection
import app.strata.launcher.core.model.IndexLetter
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.settings.SettingsActivity
import app.strata.launcher.testing.fakes.FakeAppListSource
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fixtures.AppFixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** HomeActivity with the M1 list layer (Robolectric). Device halves are in androidTest. */
@RunWith(AndroidJUnit4::class)
class HomeListActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zz =
        AppFixtures.app("Zz Test", pkg = "fixture.zz").let {
            VisibleApp(it.key, it.packageName, it.systemLabel, it.systemLabel, "Z", it.profileKind, hidden = false)
        }
    private val list =
        AppList(
            sections = listOf(AppSection("Z", listOf(zz))),
            index = listOf(IndexLetter("Z", 0, 1)),
            hiddenCount = 0,
            fromSnapshot = false,
        )

    private fun start(graph: TestAppGraph): HomeActivity {
        graph.install()
        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(ComponentName(context, HomeActivity::class.java))
        val activity = Robolectric.buildActivity(HomeActivity::class.java, intent).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    private fun graph() =
        TestAppGraph(FakeRoleController(defaultHome = true), onboarded = true).let {
            TestAppGraph(
                roleController = it.roleController,
                settingsRepository = it.settingsRepository,
                appList = FakeAppListSource(list),
            )
        }

    /** FR-LIFE-01 / ANDROID_PLATFORM §3.1: the catalog starts with the activity and reconciles on every start. */
    @Test
    fun catalogStartsAndReconcilesOnStart() {
        val g = graph()
        start(g)
        assertThat(g.catalogStarts).isEqualTo(1)
        assertThat(g.appCatalog.reconcileRequests).isEqualTo(1)
    }

    /** INTERACTION_SPEC §3: "All apps" opens the list; Back returns to HOME_REST. */
    @Test
    fun allAppsOpensListAndBackCloses() {
        val activity = start(graph())
        compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
        compose.onNodeWithTag(AppListTestTags.LIST).assertExists()
        compose.onNodeWithTag(AppListTestTags.row(zz)).assertExists()
        activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.onNodeWithTag(AppListTestTags.LIST).assertDoesNotExist()
    }

    /** FR-APPS-02: a tap launches through the AppLauncher and is logged. */
    @Test
    fun tapLaunchesAndLogs() {
        val g = graph()
        start(g)
        compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
        compose.onNodeWithTag(AppListTestTags.row(zz)).performClick()
        compose.waitForIdle()
        assertThat(g.appLauncher.launches.map { it.first }).containsExactly(zz.key)
        assertThat(g.launchLog.events.map { it.first }).containsExactly(zz.key)
    }

    /** T-APPS-03 (Robolectric half): failure → "Couldn't open …" with App info, then reconcile; no crash. */
    @Test
    fun tApps03_launchFailureSnackbarAndReconcile() {
        val g = graph()
        g.appLauncher.failing += zz.key
        start(g)
        compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
        compose.onNodeWithTag(AppListTestTags.row(zz)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Couldn't open Zz Test").assertExists()
        compose.onNodeWithText("App info").performClick()
        compose.waitForIdle()
        assertThat(g.appLauncher.appInfoRequests).containsExactly(zz.key)
        assertThat(g.appCatalog.reconcileRequests).isEqualTo(2) // onStart + failure
    }

    /** T-LIST-09 (activity half): the end rows open the settings activity. */
    @Test
    fun tList09_endRowsOpenSettings() {
        val activity = start(graph())
        compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
        val scrollable = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
        scrollable.performScrollToNode(hasTestTag(AppListTestTags.END_SETTINGS))
        compose.onNodeWithTag(AppListTestTags.END_SETTINGS).performClick()
        compose.waitForIdle()
        val settings = shadowOf(activity).nextStartedActivity
        assertThat(settings.component).isEqualTo(ComponentName(context, SettingsActivity::class.java))
        assertThat(settings.action).isEqualTo(Intent.ACTION_APPLICATION_PREFERENCES)
        compose.onNodeWithTag(AppListTestTags.END_HIDDEN).performClick()
        compose.waitForIdle()
        assertThat(shadowOf(activity).nextStartedActivity.action).isEqualTo(SettingsActivity.ACTION_HIDDEN_APPS)
    }
}
