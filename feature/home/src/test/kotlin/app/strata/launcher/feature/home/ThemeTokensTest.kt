package app.strata.launcher.feature.home

import android.text.format.DateFormat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.platformapi.system.HapticKind
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.model.IndexSide
import app.strata.launcher.core.theme.model.IndexVisibility
import app.strata.launcher.core.theme.model.SearchPosition
import app.strata.launcher.core.theme.model.SearchStyle
import app.strata.launcher.core.theme.parse.ThemeParser
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import app.strata.launcher.core.theme.resolve.ThemeGlobals
import app.strata.launcher.core.theme.resolve.ThemeResolver
import app.strata.launcher.core.theme.store.ThemePair
import app.strata.launcher.core.theme.store.ThemeState
import app.strata.launcher.core.ui.AlphabetIndexTestTags
import app.strata.launcher.core.ui.StrataTheme
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.testing.fakes.FakeThemeRepository
import app.strata.launcher.testing.fixtures.AppFixtures.app
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Theme tokens on home and list (Robolectric Compose, level C): T-THEME-13…16, T-THEME-18. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ThemeTokensTest {
    @get:Rule
    val compose = createComposeRule()

    private val apps = listOf("Alpha", "Bravo", "Camera", "Delta", "Maps").map { app(it) }
    private val base = StrataDefault.resolved(systemDark = false)

    private fun resolve(
        json: String,
        globals: ThemeGlobals = ThemeGlobals(),
    ): ResolvedTheme {
        val doc = (ThemeParser().parse(json.toByteArray()) as Outcome.Ok).value.document
        return ThemeResolver.resolve(doc, PlatformInputs(apiLevel = 34), globals).theme
    }

    private fun theme(groups: String) = """{"schemaVersion":1,"id":"$ID","name":"T",$groups}"""

    // ---- T-THEME-18 -------------------------------------------------------------------------------

    /** Scrub ticks only with `haptics.mode = followSystem` and the master switch on. */
    @Test
    fun tTheme18_hapticsModes() {
        fun ticks(
            mode: String,
            master: Boolean,
        ): Int {
            val resolved = resolve(theme(""""haptics":{"mode":"$mode"}"""), ThemeGlobals(hapticsEnabled = master))
            val repo = FakeThemeRepository(ThemeState(themes = ThemePair(resolved, resolved), loaded = true))
            val h =
                HomeHarness(
                    apps,
                    settings =
                        app.strata.launcher.core.model
                            .Settings(onboardingDone = true, hapticsEnabled = master),
                    themeRepository = repo,
                )
            h.vm.onAction(HomeAction.IndexScrub(first = true))
            repeat(5) { h.vm.onAction(HomeAction.IndexScrub(first = false)) }
            return h.haptics.count(HapticKind.TICK)
        }
        assertThat(ticks("followSystem", master = true)).isEqualTo(5)
        assertThat(ticks("off", master = true)).isEqualTo(0)
        assertThat(ticks("followSystem", master = false)).isEqualTo(0)
        assertThat(ticks("off", master = false)).isEqualTo(0)
    }

    // ---- T-THEME-15 -------------------------------------------------------------------------------

    @Test
    fun tTheme15_hiddenSearchBar_swipeStillOpensSearch() {
        val h = HomeHarness(apps, apps.take(2).map { it.key })
        h.setContent(compose, base.copy(search = base.search.copy(position = SearchPosition.HIDDEN)))
        compose.onNodeWithTag(HomeTestTags.PILL).assertDoesNotExist()
        compose.onRoot().performTouchInput { swipeUp(startY = centerY, endY = centerY - 400f, durationMillis = 200) }
        compose.waitForIdle()
        assertThat(h.state.layer).isEqualTo(HomeLayer.SEARCH)
    }

    @Test
    fun tTheme15_stylesAndPositions() {
        val h = HomeHarness(apps, apps.take(2).map { it.key })
        var current by mutableStateOf(base)
        compose.setContent { StrataTheme(current) { h.Home() } }
        for (style in SearchStyle.entries) {
            for (position in listOf(SearchPosition.TOP, SearchPosition.BOTTOM)) {
                current = base.copy(search = base.search.copy(style = style, position = position))
                compose.waitForIdle()
                val pill = compose.onNodeWithTag(HomeTestTags.PILL).assertIsDisplayed().getBoundsInRoot()
                val height = compose.onRoot().getBoundsInRoot().bottom
                if (position == SearchPosition.TOP) {
                    assertThat(pill.top.value).isLessThan(height.value / 2)
                } else {
                    assertThat(pill.top.value).isGreaterThan(height.value / 2)
                }
            }
        }
    }

    // ---- T-THEME-16 -------------------------------------------------------------------------------

    @Test
    fun tTheme16_listOnly_indexOnlyInTheList() {
        val h = HomeHarness(apps)
        h.setContent(
            compose,
            base.copy(alphabetIndex = base.alphabetIndex.copy(visibility = IndexVisibility.LIST_ONLY)),
        )
        compose.onNodeWithTag(HomeTestTags.HOME_INDEX).assertDoesNotExist()
        h.vm.onAction(HomeAction.OpenList)
        compose.waitForIdle()
        compose.onNodeWithTag(AlphabetIndexTestTags.INDEX).assertExists()
    }

    @Test
    fun tTheme16_never_noIndexButTalkBackJumpRemains() {
        val h = HomeHarness(apps)
        h.setContent(compose, base.copy(alphabetIndex = base.alphabetIndex.copy(visibility = IndexVisibility.NEVER)))
        compose.onNodeWithTag(HomeTestTags.HOME_INDEX).assertDoesNotExist()
        h.vm.onAction(HomeAction.OpenList)
        compose.waitForIdle()
        compose.onNodeWithTag(AlphabetIndexTestTags.INDEX).assertDoesNotExist()
        // TalkBack keeps "Jump to letter…" on the list (FR-A11Y-02) although the index is not drawn.
        val labels =
            compose
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
                .fetchSemanticsNodes()
                .flatMap { node -> node.config[SemanticsActions.CustomActions].map { it.label } }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertThat(labels).contains(context.getString(R.string.list_jump_to_letter))
    }

    @Test
    fun tTheme16_sideMirrorsInRtl() {
        val h = HomeHarness(apps)
        var current by mutableStateOf(base.copy(alphabetIndex = base.alphabetIndex.copy(side = IndexSide.END)))
        compose.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) { StrataTheme(current) { h.Home() } }
        }
        val width =
            compose
                .onRoot()
                .getBoundsInRoot()
                .right.value
        val end = compose.onNodeWithTag(HomeTestTags.HOME_INDEX).getBoundsInRoot()
        assertThat(end.right.value).isAtMost(width / 2) // end = left in RTL
        current = base.copy(alphabetIndex = base.alphabetIndex.copy(side = IndexSide.START))
        compose.waitForIdle()
        val start = compose.onNodeWithTag(HomeTestTags.HOME_INDEX).getBoundsInRoot()
        assertThat(start.left.value).isAtLeast(width / 2)
    }

    // ---- T-THEME-14 -------------------------------------------------------------------------------

    /** Icons and labels both off → labels forced on; a theme change never changes the favourite set. */
    @Test
    fun tTheme14_labelsForcedOn_themeNeverChangesFavourites() {
        val forced = resolve(theme(""""favorites":{"showIcons":false,"showLabels":false}"""))
        assertThat(forced.favorites.showLabels).isTrue()
        val h = HomeHarness(apps, apps.take(3).map { it.key })
        var current by mutableStateOf(forced)
        compose.setContent { StrataTheme(current) { h.Home() } }
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        val before = h.state.favoriteKeys
        current = resolve(theme(""""favorites":{"showIcons":true,"alignment":"end","textScale":2.0}"""))
        compose.waitForIdle()
        current = StrataDefault.resolved(systemDark = true)
        compose.waitForIdle()
        assertThat(h.state.favoriteKeys).isEqualTo(before)
    }

    // ---- T-THEME-13 (R) ---------------------------------------------------------------------------

    /** The date text is the `getBestDateTimePattern(locale, skeleton)` output (en-US, de, ja, ar, hi). */
    private fun assertDatePattern(tag: String) {
        val locale = Locale.forLanguageTag(tag)
        val h = HomeHarness(apps)
        h.setContent(compose)
        val shown =
            compose
                .onNodeWithTag(HomeTestTags.DATE)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .joinToString("") { it.text }
        val expected =
            SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, base.clock.dateSkeleton), locale).format(Date())
        assertThat(shown).isEqualTo(expected)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun tTheme13_dateEnUs() = assertDatePattern("en-US")

    @Test
    @Config(qualifiers = "de-w411dp-h891dp-xxhdpi")
    fun tTheme13_dateDe() = assertDatePattern("de")

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun tTheme13_dateJa() = assertDatePattern("ja")

    @Test
    @Config(qualifiers = "ar-w411dp-h891dp-xxhdpi")
    fun tTheme13_dateAr() = assertDatePattern("ar")

    @Test
    @Config(qualifiers = "hi-w411dp-h891dp-xxhdpi")
    fun tTheme13_dateHi() = assertDatePattern("hi")

    private companion object {
        const val ID = "11111111-2222-4333-8444-555555555555"
    }
}
