package app.strata.launcher.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.AppSection
import app.strata.launcher.core.model.IndexLetter
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.feature.search.SearchTestTags
import app.strata.launcher.testing.fakes.FakeAppListSource
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fixtures.SearchFixtures.visible
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/** HomeActivity with the M2 search layer (Robolectric, level C). Device halves are in androidTest. */
@RunWith(AndroidJUnit4::class)
class HomeSearchActivityTest {
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

    private fun start(): Pair<HomeActivity, TestAppGraph> {
        val g =
            TestAppGraph(FakeRoleController(defaultHome = true), onboarded = true).let {
                TestAppGraph(
                    roleController = it.roleController,
                    settingsRepository = it.settingsRepository,
                    appList = FakeAppListSource(list),
                )
            }
        g.install()
        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(ComponentName(context, HomeActivity::class.java))
        controller = Robolectric.buildActivity(HomeActivity::class.java, intent).setup()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        return activity to g
    }

    private fun press(
        activity: HomeActivity,
        keyCode: Int,
    ) {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        compose.waitForIdle()
    }

    /** T-SEARCH-02 (C): a printable hardware key on home opens search with that query and results. */
    @Test
    fun tSearch02_hardwareKeyOnHome() {
        val (activity, _) = start()
        press(activity, KeyEvent.KEYCODE_M)
        compose.onNodeWithTag(SearchTestTags.FIELD).assert(hasText("m"))
        compose.onNodeWithTag(SearchTestTags.row(maps)).assertExists()
        compose.onNodeWithTag(SearchTestTags.row(music)).assertExists()
    }

    /** T-SEARCH-02 (C): the same from the list layer; Back returns to the list (SEARCH(list) → LIST). */
    @Test
    fun tSearch02_hardwareKeyOnListAndBackToList() {
        val (activity, _) = start()
        compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
        press(activity, KeyEvent.KEYCODE_M)
        compose.onNodeWithTag(SearchTestTags.FIELD).assert(hasText("m"))
        activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.onNodeWithTag(SearchTestTags.LAYER).assertDoesNotExist()
        compose.onNodeWithTag(AppListTestTags.LIST).assertExists()
    }

    /** FR-SEARCH-01: the list's search field opens search; a result launches, logs and closes search. */
    @Test
    fun listFieldOpensSearchAndLaunchCloses() {
        val (_, g) = start()
        compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
        compose.onNodeWithTag(AppListTestTags.SEARCH_FIELD).performClick()
        compose.onNodeWithTag(SearchTestTags.LAYER).assertExists()
        compose.onNodeWithTag(SearchTestTags.FIELD).assert(hasText(""))
        compose.onNodeWithTag(SearchTestTags.FIELD).performTextInput("mus")
        compose.onNodeWithTag(SearchTestTags.row(music)).performClick()
        compose.waitForIdle()
        assertThat(g.appLauncher.launches.map { it.first }).containsExactly(music.key)
        assertThat(g.launchLog.events.map { it.first }).containsExactly(music.key)
        compose.onNodeWithTag(SearchTestTags.LAYER).assertDoesNotExist()
        compose.onNodeWithTag(AppListTestTags.LIST).assertExists()
    }

    /** INTERACTION_SPEC §6: Home while searching closes and clears search and returns to rest. */
    @Test
    fun homePressClosesSearch() {
        val (activity, _) = start()
        press(activity, KeyEvent.KEYCODE_M)
        val home =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(ComponentName(context, HomeActivity::class.java))
        controller.newIntent(home)
        compose.waitForIdle()
        compose.onNodeWithTag(SearchTestTags.LAYER).assertDoesNotExist()
        compose.onNodeWithTag(AppListTestTags.LIST).assertDoesNotExist()
    }
}
