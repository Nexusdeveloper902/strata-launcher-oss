package app.strata.launcher.a11y

import android.content.Context
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import app.strata.launcher.core.theme.store.ThemePair
import app.strata.launcher.core.theme.store.ThemeState
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.SheetTestTags
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.feature.search.SearchTestTags
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.testing.fakes.FakeThemeRepository
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * T-A11Y-12 (C half), NFR-A11Y-05, FR-THEME-16: with "Remove animations" (the resolved theme then has animations off)
 * opening search, the list and the sheet, and hiding a row, are instant cuts: what the first frame shows does not
 * move afterwards. The same steps with animations on move (the control), so the check is not vacuous. The theme
 * cross-fade and the sheet timing per scale are T-THEME-17 ([app.strata.launcher.core.ui.ThemeRenderingTest]).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ReducedMotionTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var graph: TestAppGraph

    private fun theme(dark: Boolean, animations: Boolean): ResolvedTheme {
        val t = StrataDefault.resolved(systemDark = dark)
        return if (animations) {
            t
        } else {
            t.copy(
                animation = t.animation.copy(durationScale = 0.0, themeDurationScale = 0.0),
            )
        }
    }

    private fun start(animations: Boolean) {
        val pair = ThemePair(theme(false, animations), theme(true, animations))
        graph =
            KeyboardFixture
                .graph()
                .let {
                    TestAppGraph(
                        roleController = it.roleController,
                        settingsRepository = it.settingsRepository,
                        appList = it.appList,
                        favorites = it.favorites,
                        overrides = it.overrides,
                        themeRepository = FakeThemeRepository(ThemeState(themes = pair, loaded = true)),
                    )
                }.install()
        Robolectric.buildActivity(HomeActivity::class.java, KeyboardFixture.homeIntent(context)).setup()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    /** Runs [action], then returns the top of [tag] on the first frame and once everything has settled. */
    private fun firstAndSettled(
        tag: String,
        action: () -> Unit,
    ): Pair<Float?, Float?> {
        action()
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val first = top(tag)
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        return first to top(tag)
    }

    private fun top(tag: String): Float? =
        compose
            .onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.boundsInRoot
            ?.top

    private fun openSheet(): Pair<Float?, Float?> {
        compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        return firstAndSettled(SheetTestTags.FAVORITE) {
            compose
                .onNodeWithTag(
                    AppListTestTags.row(KeyboardFixture.alpha),
                ).performSemanticsAction(SemanticsActions.OnLongClick)
        }
    }

    @Test
    fun tA11y12_search_isACut() {
        start(animations = false)
        val (first, settled) =
            firstAndSettled(
                SearchTestTags.FIELD,
            ) { compose.onNodeWithTag(HomeTestTags.PILL).performClick() }
        assertThat(first).isNotNull()
        assertThat(first).isEqualTo(settled)
    }

    @Test
    fun tA11y12_list_isACut() {
        start(animations = false)
        val (first, settled) =
            firstAndSettled(AppListTestTags.LIST) {
                compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
            }
        assertThat(first).isNotNull()
        assertThat(first).isEqualTo(settled)
    }

    @Test
    fun tA11y12_sheet_isACut() {
        start(animations = false)
        val (first, settled) = openSheet()
        assertThat(first).isNotNull()
        assertThat(first).isEqualTo(settled)
    }

    /** Control: with animations on, the sheet is still moving on its first frame. */
    @Test
    fun sheetMovesWithAnimationsOn() {
        start(animations = true)
        val (first, settled) = openSheet()
        assertWithMessage("the sheet rises with motion.medium").that(first).isNotEqualTo(settled)
    }

    /** Hiding a row: the row is gone on the first frame and the next row does not slide up afterwards. */
    @Test
    fun tA11y12_hideRow_isACut() {
        start(animations = false)
        openSheet()
        val next = AppListTestTags.row(KeyboardFixture.maps)
        val (first, settled) = firstAndSettled(next) { compose.onNodeWithTag(SheetTestTags.HIDE).performClick() }
        compose.onNodeWithTag(SheetTestTags.SHEET).assertDoesNotExist()
        assertThat(first).isEqualTo(settled)
    }

    private companion object {
        const val SETTLE_MS = 2_000L
    }
}
