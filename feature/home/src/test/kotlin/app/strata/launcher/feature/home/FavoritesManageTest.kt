package app.strata.launcher.feature.home

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.AppEntry
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.platformapi.apps.FavoritesRepository
import app.strata.launcher.testing.fixtures.AppFixtures.app
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Owner request "a way to modify what your favourite apps are" (FR-FAV-01/02, Proposed): Edit favourites manages the
 * set: remove with Undo (T-FAV-06), "Add apps" picker with search and checkboxes (T-FAV-07), the cap of 12 in the
 * picker (T-FAV-08). Robolectric Compose, level C.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class FavoritesManageTest {
    @get:Rule
    val compose = createComposeRule()

    private val apps: List<AppEntry> =
        listOf("Alpha", "Bravo", "Camera", "Delta", "Echo", "Maps", "Music").map { app(it) }

    private fun visible(h: HomeHarness, entry: AppEntry) =
        h.state.list!!
            .sections
            .flatMap { it.apps }
            .first { it.key == entry.key }

    private fun edit(favorites: Int = 3): HomeHarness {
        val h = HomeHarness(apps, apps.take(favorites).map { it.key })
        h.setContent(compose)
        h.vm.onAction(HomeAction.EditFavorites)
        compose.waitForIdle()
        return h
    }

    private fun done(h: HomeHarness) {
        compose.onNodeWithTag(FavEditTestTags.DONE).performClick()
        compose.waitForIdle()
    }

    /** T-FAV-06: remove shows at once; Undo puts it back in place; Done saves. */
    @Test
    fun tFav06_removeWithUndo() {
        val h = edit()
        val bravo = visible(h, apps[1])
        compose.onNodeWithTag(FavEditTestTags.remove(bravo)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(FavEditTestTags.row(bravo)).assertDoesNotExist()
        assertThat(h.state.favEdit.map { it.key }).containsExactly(apps[0].key, apps[2].key).inOrder()
        compose.onNodeWithText("Bravo removed from favourites").assertExists()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertThat(h.state.favEdit.map { it.key }).containsExactly(apps[0].key, apps[1].key, apps[2].key).inOrder()
        done(h)
        assertThat(h.favoritesRepo.state.value).containsExactly(apps[0].key, apps[1].key, apps[2].key).inOrder()
    }

    /** T-FAV-06: a removal without Undo is saved by Done. */
    @Test
    fun tFav06_removeSaved() {
        val h = edit()
        compose.onNodeWithTag(FavEditTestTags.remove(visible(h, apps[0]))).performClick()
        done(h)
        assertThat(h.favoritesRepo.state.value).containsExactly(apps[1].key, apps[2].key).inOrder()
    }

    /** T-FAV-06: the remove control has a spoken name and a 48 dp target (NFR-A11Y-01/03). */
    @Test
    fun tFav06_removeControlAccessible() {
        val h = edit()
        val alpha = visible(h, apps[0])
        val node = compose.onNodeWithTag(FavEditTestTags.remove(alpha))
        assertThat(node.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
            .containsExactly("Remove Alpha from favourites")
        node.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    /** T-FAV-07: the picker checks current favourites, searches, appends new picks; Cancel changes nothing. */
    @Test
    fun tFav07_addWithPicker() {
        val h = edit(favorites = 2)
        compose.onNodeWithTag(FavEditTestTags.ADD_APPS).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(FavEditTestTags.pick(apps[0].key)).assertIsOn()
        compose.onNodeWithTag(FavEditTestTags.pick(apps[3].key)).assertIsOff()
        compose.onNodeWithTag(FavEditTestTags.PICKER_SEARCH).performTextInput("mu")
        compose.waitForIdle()
        assertThat(
            h.state.favPicker!!
                .apps
                .map { it.displayLabel },
        ).containsExactly("Music")
        compose.onNodeWithTag(FavEditTestTags.pick(apps[6].key)).performClick()
        compose.onNodeWithTag(FavEditTestTags.PICKER_CONFIRM).performClick()
        compose.waitForIdle()
        assertThat(h.state.favEdit.map { it.key }).containsExactly(apps[0].key, apps[1].key, apps[6].key).inOrder()
        // Unchecking a favourite in the picker removes it.
        compose.onNodeWithTag(FavEditTestTags.ADD_APPS).performClick()
        compose.onNodeWithTag(FavEditTestTags.pick(apps[0].key)).performClick()
        compose.onNodeWithTag(FavEditTestTags.PICKER_CONFIRM).performClick()
        // Cancel keeps what is there.
        compose.onNodeWithTag(FavEditTestTags.ADD_APPS).performClick()
        compose.onNodeWithTag(FavEditTestTags.pick(apps[4].key)).performClick()
        compose.onNodeWithTag(FavEditTestTags.PICKER_CANCEL).performClick()
        done(h)
        assertThat(h.favoritesRepo.state.value).containsExactly(apps[1].key, apps[6].key).inOrder()
    }

    /** T-FAV-07: hidden apps are not offered. */
    @Test
    fun tFav07_hiddenNotOffered() {
        val h = HomeHarness(apps, emptyList())
        runBlocking { h.overrides.setHidden(apps[3].key, ProfileKind.MAIN, hidden = true) }
        h.setContent(compose)
        h.vm.onAction(HomeAction.EditFavorites)
        h.vm.onAction(HomeAction.OpenFavPicker)
        compose.waitForIdle()
        assertThat(
            h.state.favPicker!!
                .apps
                .map { it.key },
        ).doesNotContain(apps[3].key)
        compose.onNodeWithTag(FavEditTestTags.pick(apps[3].key)).assertDoesNotExist()
    }

    /** T-FAV-08: the picker stops at 12 with the explanation; nothing is added silently. */
    @Test
    fun tFav08_pickerCap() {
        val many = (1..14).map { app("App %02d".format(it)) }
        val h = HomeHarness(many, many.take(FavoritesRepository.MAX - 1).map { it.key })
        h.setContent(compose)
        h.vm.onAction(HomeAction.EditFavorites)
        h.vm.onAction(HomeAction.OpenFavPicker)
        compose.waitForIdle()
        h.vm.onAction(HomeAction.FavPickerToggle(many[12].key))
        h.vm.onAction(HomeAction.FavPickerToggle(many[13].key))
        compose.waitForIdle()
        compose.onNodeWithTag(FavEditTestTags.PICKER_LIMIT).assertExists()
        assertThat(h.state.favPicker!!.picked).hasSize(FavoritesRepository.MAX)
        assertThat(h.state.favPicker!!.picked).doesNotContain(many[13].key)
        compose.onNodeWithTag(FavEditTestTags.PICKER_CONFIRM).performClick()
        done(h)
        assertThat(h.favoritesRepo.state.value).hasSize(FavoritesRepository.MAX)
        assertThat(
            h.favoritesRepo.state.value
                .last(),
        ).isEqualTo(many[12].key)
    }
}
