package app.strata.launcher.feature.search

import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.ui.StrataTheme
import app.strata.launcher.testing.fixtures.SearchFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** S-SEARCH Compose tests on Robolectric (level C). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp")
class SearchScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var closes = 0

    private fun show(
        h: SearchHarness,
        seed: String = "",
    ) {
        compose.setContent {
            StrataTheme(StrataDefault.resolved(systemDark = false)) {
                val state by h.viewModel.state.collectAsStateWithLifecycle()
                SearchRoute(
                    state = state,
                    effects = h.viewModel.effects,
                    onAction = h.viewModel::onAction,
                    onClose = { closes++ },
                    seed = seed,
                    session = 1,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun row(
        h: SearchHarness,
        label: String,
    ) = compose.onNodeWithTag(SearchTestTags.row(h.app(label)))

    private val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)

    /** T-SEARCH-02 (C half, search side): the seed key is the query and results show. */
    @Test
    fun tSearch02_seededQuery() {
        val h = SearchHarness()
        show(h, seed = "m")
        compose.onNodeWithTag(SearchTestTags.FIELD).assert(hasText("m")).assertIsFocused()
        row(h, "Maps").assertExists()
        assertThat(h.viewModel.state.value.query).isEqualTo("m")
    }

    /** T-SEARCH-07 (C half): the hidden app carries the "Hidden" tag; gone with the setting off. */
    @Test
    fun tSearch07_hiddenTag() {
        val h = SearchHarness()
        show(h, seed = "hid")
        row(h, "Hidden Diary").assert(hasText("Hidden", substring = false))
        h.settings.set(Settings(onboardingDone = true, showHiddenInSearch = false))
        compose.waitForIdle()
        row(h, "Hidden Diary").assertDoesNotExist()
        compose.onNodeWithTag(SearchTestTags.NO_RESULTS).assertExists()
    }

    /** T-SEARCH-09: Down twice + Enter launches the 3rd item; IME Go launches the top and resets; Esc closes. */
    @Test
    fun tSearch09_keyboardOperation() {
        val h = SearchHarness()
        show(h)
        val field = compose.onNodeWithTag(SearchTestTags.FIELD)
        field.performTextInput("cal")
        compose.waitForIdle()
        row(h, "Calculator").assert(selected) // the ring starts on the top result
        field.performKeyInput { pressKey(Key.DirectionDown) }
        field.performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        row(h, "Calligraphy").assert(selected)
        row(h, "Calculator").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        field.performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        assertThat(h.launcher.launches.map { it.first }).containsExactly(h.app("Calligraphy").key)
        assertThat(closes).isEqualTo(1)
        assertThat(h.viewModel.state.value.query).isEmpty()

        field.performTextReplacement("cal")
        compose.waitForIdle()
        // The launch-log boost now puts Calligraphy on top; Go launches whatever is on top.
        assertThat(
            h.viewModel.state.value.items
                .first()
                .displayLabel,
        ).isEqualTo("Calligraphy")
        field.performImeAction()
        compose.waitForIdle()
        assertThat(
            h.launcher.launches
                .last()
                .first,
        ).isEqualTo(h.app("Calligraphy").key)
        assertThat(closes).isEqualTo(2)
        assertThat(h.viewModel.state.value.query).isEmpty()

        field.performTextReplacement("cal")
        field.performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        assertThat(closes).isEqualTo(3)
        assertThat(h.launcher.launches).hasSize(2)
    }

    /** T-SEARCH-11: empty query shows ≤ 8 recent apps, most recent first, under a heading. */
    @Test
    fun tSearch11_recentApps() {
        val h = SearchHarness()
        runBlocking {
            h.launched(
                "Maps",
                "Notes",
                "Weather",
                "Clock",
                "Camera",
                "Calendar",
                "Calculator",
                "Calligraphy",
                "Maps",
                "Notes",
            )
        }
        show(h)
        compose.onNodeWithTag(SearchTestTags.RECENT_HEADER).assertExists()
        val shown =
            h.viewModel.state.value.items
                .map { it.displayLabel }
        assertThat(shown)
            .containsExactly("Notes", "Maps", "Calligraphy", "Calculator", "Calendar", "Camera", "Clock", "Weather")
            .inOrder()
        row(h, "Notes").assertExists()
    }

    /** FR-SEARCH-05: recents disabled → only the hint. */
    @Test
    fun recentsDisabledShowsOnlyHint() {
        val h = SearchHarness(settings = Settings(onboardingDone = true, recentsEnabled = false))
        runBlocking { h.launched("Maps") }
        show(h)
        compose.onNodeWithTag(SearchTestTags.RECENT_HEADER).assertDoesNotExist()
        row(h, "Maps").assertDoesNotExist()
        compose.onNodeWithText("Search apps").assertExists()
    }

    /** T-SEARCH-15 (Compose half): with a handler the store row shows and hands off "zzq"; without, absent. */
    @Test
    fun tSearch15_storeRow() {
        val h = SearchHarness()
        show(h, seed = "zzq")
        compose.onNodeWithText("No apps match “zzq”").assertExists()
        compose.onNodeWithTag(SearchTestTags.STORE_ROW).performClick()
        compose.waitForIdle()
        assertThat(h.intents.storeSearches).containsExactly("zzq")
        assertThat(closes).isEqualTo(1)
    }

    @Test
    fun tSearch15_noStoreHandler() {
        val h = SearchHarness(storeHandler = false)
        show(h, seed = "zzq")
        compose.onNodeWithTag(SearchTestTags.NO_RESULTS).assertExists()
        compose.onNodeWithTag(SearchTestTags.STORE_ROW).assertDoesNotExist()
    }

    /** VISUAL_SPEC §9.1: the clear button empties the query. */
    @Test
    fun clearButton() {
        val h = SearchHarness()
        show(h, seed = "ma")
        compose.onNodeWithTag(SearchTestTags.CLEAR).performClick()
        compose.waitForIdle()
        assertThat(h.viewModel.state.value.query).isEmpty()
        compose.onNodeWithTag(SearchTestTags.CLEAR).assertDoesNotExist()
    }

    /** A tap launches with the row's source bounds (FR-APPS-02). */
    @Test
    fun tapLaunchesWithOrigin() {
        val h = SearchHarness()
        show(h, seed = "notes")
        row(h, "Notes").performClick()
        compose.waitForIdle()
        val (key, origin) = h.launcher.launches.single()
        assertThat(key).isEqualTo(h.app("Notes").key)
        assertThat(origin).isNotNull()
    }

    /** Editing the query to one with the same result count brings the new top result back into view. */
    @Test
    fun queryChangeScrollsSelectionIntoView() {
        val labels = (0 until 30).map { "Alpha %02d".format(it) } + (0 until 30).map { "Bravo %02d".format(it) }
        val h = SearchHarness(apps = labels.map { SearchFixtures.visible(it) })
        show(h, seed = "alpha")
        row(h, "Alpha 00").assertIsDisplayed()
        compose.onNodeWithTag(SearchTestTags.RESULTS).performScrollToIndex(25)
        compose.waitForIdle()
        row(h, "Alpha 00").assertDoesNotExist()
        compose.onNodeWithTag(SearchTestTags.FIELD).performTextReplacement("bravo")
        compose.waitForIdle()
        assertThat(h.viewModel.state.value.items).hasSize(30)
        row(h, "Bravo 00").assert(selected).assertIsDisplayed()
    }
}
