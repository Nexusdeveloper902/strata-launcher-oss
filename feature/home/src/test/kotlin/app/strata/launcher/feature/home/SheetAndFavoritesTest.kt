package app.strata.launcher.feature.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.AppFlags
import app.strata.launcher.core.model.Catalog
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.platformapi.system.HapticKind
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.model.IndexVisibility
import app.strata.launcher.core.ui.AlphabetIndexTestTags
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.testing.fixtures.AppFixtures.app
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** App sheet, favourites, FAVEDIT and accessibility actions (Robolectric Compose, level C). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SheetAndFavoritesTest {
    @get:Rule
    val compose = createComposeRule()

    private val user = app("Maps", pkg = "fixture.maps")
    private val system = app("Clock", pkg = "fixture.clock", flags = AppFlags.SYSTEM)
    private val updated = app("Browser", pkg = "fixture.browser", flags = AppFlags.SYSTEM or AppFlags.UPDATED_SYSTEM)
    private val alpha = app("Alpha")
    private val bravo = app("Bravo")
    private val charlie = app("Charlie")
    private val radio = app("Radio")
    private val reader = app("Reader")
    private val apps = listOf(user, system, updated, alpha, bravo, charlie, radio, reader)

    private fun openSheetFromList(
        h: HomeHarness,
        label: String,
    ) {
        h.vm.onAction(HomeAction.OpenList)
        compose.waitForIdle()
        val row =
            h.state.list!!
                .allApps
                .first { it.displayLabel == label }
        compose.onNode(hasTestTag(AppListTestTags.LIST)).assertExists()
        compose.onNode(hasTestTag(AppListTestTags.row(row))).performScrollTo().performTouchInput { longClick() }
        compose.waitForIdle()
    }

    /** T-APPS-04: system → no Uninstall; updated system → "Uninstall updates"; user → Uninstall; all have the rest. */
    @Test
    fun tApps04_sheetVariants() {
        val h = HomeHarness(apps)
        h.setContent(compose)

        fun common() {
            listOf(
                SheetTestTags.NAME,
                SheetTestTags.FAVORITE,
                SheetTestTags.CHANGE_ICON,
                SheetTestTags.HIDE,
                SheetTestTags.APP_INFO,
            ).forEach { compose.onNodeWithTag(it).assertExists() }
        }
        openSheetFromList(h, "Clock")
        common()
        compose.onNodeWithTag(SheetTestTags.UNINSTALL).assertDoesNotExist()
        h.vm.onAction(HomeAction.CloseModal)
        openSheetFromList(h, "Browser")
        common()
        compose.onNodeWithTag(SheetTestTags.UNINSTALL).assertExists()
        compose.onNodeWithText("Uninstall updates").assertExists()
        h.vm.onAction(HomeAction.CloseModal)
        openSheetFromList(h, "Maps")
        common()
        compose.onNodeWithText("Uninstall").assertExists()
        compose.onNodeWithTag(SheetTestTags.UNINSTALL).performClick()
        assertThat(h.launcher.uninstallRequests).containsExactly(user.key)
        assertThat(h.state.modal).isEqualTo(HomeModal.None)
    }

    /** T-APPS-05 (list and favourite; the search half is in the activity test): the same sheet from each surface. */
    @Test
    fun tApps05_sheetFromListAndFavourite() {
        val h = HomeHarness(apps, favorites = listOf(user.key))
        h.setContent(compose)
        compose.onNodeWithTag(HomeTestTags.favorite(h.state.favorites.single())).performTouchInput { longClick() }
        compose.waitForIdle()
        assertThat(h.state.modal).isEqualTo(HomeModal.Sheet(user.key))
        compose.onNodeWithTag(SheetTestTags.NAME).assertExists()
        h.vm.onAction(HomeAction.CloseModal)
        compose.waitForIdle()
        openSheetFromList(h, "Maps")
        assertThat(h.state.modal).isEqualTo(HomeModal.Sheet(user.key))
        compose.onNodeWithTag(SheetTestTags.NAME).assertExists()
    }

    /** T-APPS-06: hidden X leaves list and favourites but keeps its favourite slot; unhide brings both back. */
    @Test
    fun tApps06_hideAndUnhide() {
        val h = HomeHarness(apps, favorites = listOf(alpha.key, user.key))
        h.setContent(compose)
        compose.onNodeWithTag(HomeTestTags.favorite(h.state.favorites[1])).performTouchInput { longClick() }
        compose.onNodeWithTag(SheetTestTags.HIDE).performClick()
        compose.waitForIdle()
        assertThat(h.state.favorites.map { it.key }).containsExactly(alpha.key)
        assertThat(h.favoritesRepo.state.value).containsExactly(alpha.key, user.key).inOrder()
        assertThat(
            h.state.list!!
                .sections
                .flatMap { it.apps }
                .map { it.key },
        ).doesNotContain(user.key)
        assertThat(
            h.state.list!!
                .hiddenApps
                .map { it.key },
        ).containsExactly(user.key)
        compose.onNodeWithText("Hidden").assertExists() // J-05 snackbar with Undo

        h.vm.onAction(HomeAction.SetHidden(user.key, hidden = false)) // what Settings → Hidden apps calls
        compose.waitForIdle()
        assertThat(h.state.favorites.map { it.key }).containsExactly(alpha.key, user.key).inOrder()
        assertThat(
            h.state.list!!
                .sections
                .first { it.label == "M" }
                .apps
                .map { it.key },
        ).contains(user.key)
    }

    /** T-APPS-09: Save is disabled for empty or whitespace-only names; at most 60 characters can be entered. */
    @Test
    fun tApps09_renameValidation() {
        val h = HomeHarness(apps)
        h.setContent(compose)
        h.vm.onAction(
            HomeAction.OpenSheet(
                h.state.list!!
                    .allApps
                    .first { it.key == user.key },
            ),
        )
        compose.onNodeWithTag(SheetTestTags.NAME).performClick()
        val field = compose.onNodeWithTag(SheetTestTags.RENAME_FIELD)
        field.performTextClearance()
        compose.onNodeWithTag(SheetTestTags.RENAME_SAVE).assertIsNotEnabled()
        field.performTextInput("   ")
        compose.onNodeWithTag(SheetTestTags.RENAME_SAVE).assertIsNotEnabled()
        field.performTextClearance()
        field.performTextInput("x".repeat(61))
        val text = field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertThat(text).hasLength(60)
        field.performTextClearance()
        field.performTextInput("  Nav  ")
        compose.onNodeWithTag(SheetTestTags.RENAME_SAVE).assertIsEnabled().performClick()
        compose.waitForIdle()
        assertThat(
            h.overrides.state.value[user.key]
                ?.customLabel,
        ).isEqualTo("Nav")
        compose.onNodeWithTag(SheetTestTags.RESET_NAME).performClick()
        compose.waitForIdle()
        assertThat(h.overrides.state.value[user.key]).isNull()
    }

    /** T-FAV-01: with 12 favourites, adding a 13th asks (Replace… / Cancel); nothing is added silently. */
    @Test
    fun tFav01_capOfTwelve() {
        val many = (1..13).map { app("App %02d".format(it), pkg = "fixture.app$it") }
        val h = HomeHarness(many, favorites = many.take(12).map { it.key })
        h.setContent(compose)
        val thirteenth =
            h.state.list!!
                .allApps
                .first { it.key == many[12].key }
        h.vm.onAction(HomeAction.OpenSheet(thirteenth))
        compose.onNodeWithTag(SheetTestTags.FAVORITE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTestTags.FULL).assertExists()
        assertThat(h.favoritesRepo.state.value).hasSize(12)
        compose.onNodeWithTag(SheetTestTags.FULL_REPLACE).performClick()
        val victim = h.state.favorites[3]
        compose.onNodeWithTag(SheetTestTags.replaceWith(victim)).performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(h.favoritesRepo.state.value).hasSize(12)
        assertThat(h.favoritesRepo.state.value[3]).isEqualTo(many[12].key)
    }

    /** T-FAV-02 (C half): dragging C's handle to the top gives C, A, B on Done (persistence: the Room test). */
    @Test
    fun tFav02_dragReorder() {
        val h = HomeHarness(apps, favorites = listOf(alpha.key, bravo.key, charlie.key))
        h.setContent(compose)
        h.vm.onAction(HomeAction.EditFavorites)
        compose.waitForIdle()
        val c = h.state.favEdit[2]
        val rowHeight = compose.onNodeWithTag(FavEditTestTags.row(c)).getBoundsInRoot().let { it.bottom - it.top }
        val px = rowHeight.value * compose.density.density
        compose.onNodeWithTag(FavEditTestTags.handle(c), useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, -px * 2.2f / 10f)) }
            up()
        }
        compose.waitForIdle()
        assertThat(h.state.favEdit.map { it.key }).containsExactly(charlie.key, alpha.key, bravo.key).inOrder()
        assertThat(h.haptics.count(HapticKind.GESTURE_START)).isEqualTo(1)
        assertThat(h.haptics.count(HapticKind.GESTURE_END)).isEqualTo(1)
        compose.onNodeWithTag(FavEditTestTags.DONE).performClick()
        compose.waitForIdle()
        assertThat(h.favoritesRepo.state.value).containsExactly(charlie.key, alpha.key, bravo.key).inOrder()
        assertThat(h.favoritesRepo.saveEditCalls).isEqualTo(1) // one atomic write, not remove/add/reorder
        assertThat(h.state.layer).isEqualTo(HomeLayer.REST)
    }

    /** T-FAV-03: "Move to top" on C, then "Move down" on A; the live region announces each move. */
    @Test
    fun tFav03_accessibleReorder() {
        val h = HomeHarness(apps, favorites = listOf(alpha.key, bravo.key, charlie.key))
        h.setContent(compose)
        h.vm.onAction(HomeAction.EditFavorites)
        compose.waitForIdle()
        val (a, _, c) = h.state.favEdit
        compose.onNodeWithTag(FavEditTestTags.row(c)).invokeCustomAction(compose, "Move to top")
        assertThat(h.state.favEdit.map { it.key }).containsExactly(charlie.key, alpha.key, bravo.key).inOrder()
        compose.onNodeWithTag(FavEditTestTags.ANNOUNCEMENT).assertExists()
        compose.onNodeWithText("Charlie moved to position 1 of 3").assertExists()
        compose.onNodeWithTag(FavEditTestTags.row(a)).invokeCustomAction(compose, "Move down")
        assertThat(h.state.favEdit.map { it.key }).containsExactly(charlie.key, bravo.key, alpha.key).inOrder()
        val live = compose.onNodeWithTag(FavEditTestTags.ANNOUNCEMENT).fetchSemanticsNode()
        assertThat(live.config.contains(SemanticsProperties.LiveRegion)).isTrue()
        // Home = Done (INTERACTION_SPEC §3): the order is saved.
        h.vm.onAction(HomeAction.HomePressed)
        compose.waitForIdle()
        assertThat(h.favoritesRepo.state.value).containsExactly(charlie.key, bravo.key, alpha.key).inOrder()
    }

    /** T-FAV-04 (C half): at font 200 % with 12 favourites the block scrolls; all are reachable; swipe-up scrolls first. */
    @Test
    fun tFav04_overflowScrollAtLargeFont() {
        val many = (1..12).map { app("Favourite %02d".format(it), pkg = "fixture.fav$it") }
        val h = HomeHarness(many, favorites = many.map { it.key })
        h.setContent(compose, fontScale = 2f)
        h.state.favorites.forEach { fav ->
            compose.onNodeWithTag(HomeTestTags.FAVORITES).performScrollToNode(hasTestTag(HomeTestTags.favorite(fav)))
            compose.onNodeWithTag(HomeTestTags.favorite(fav)).assertExists()
        }
        // Back to the top of the block, then swipe up inside it: it scrolls (search stays closed) until its end.
        compose
            .onNodeWithTag(
                HomeTestTags.FAVORITES,
            ).performScrollToNode(hasTestTag(HomeTestTags.favorite(h.state.favorites.first())))
        compose.onNodeWithTag(HomeTestTags.FAVORITES).performTouchInput {
            swipeUp(
                startY = centerY,
                endY =
                    centerY - 100f,
                durationMillis = 300,
            )
        }
        compose.waitForIdle()
        assertThat(h.state.layer).isEqualTo(HomeLayer.REST)
        repeat(8) {
            if (h.state.layer == HomeLayer.REST) {
                compose.onNodeWithTag(HomeTestTags.FAVORITES).performTouchInput { swipeUp(durationMillis = 300) }
                compose.waitForIdle()
            }
        }
        assertThat(h.state.layer).isEqualTo(HomeLayer.SEARCH)
    }

    /** T-FAV-05: at font 100 % with 5 favourites the block does not scroll; a swipe up inside it opens search. */
    @Test
    fun tFav05_noScrollWhenItFits() {
        val five = listOf(alpha, bravo, charlie, radio, reader)
        val h = HomeHarness(apps, favorites = five.map { it.key })
        h.setContent(compose)
        val before = compose.onNodeWithTag(HomeTestTags.favorite(h.state.favorites.first())).getBoundsInRoot()
        compose.onNodeWithTag(HomeTestTags.FAVORITES).performTouchInput {
            swipeUp(
                startY = centerY,
                endY =
                    centerY - 200f,
                durationMillis = 300,
            )
        }
        compose.waitForIdle()
        assertThat(h.state.layer).isEqualTo(HomeLayer.SEARCH)
        h.vm.onAction(HomeAction.CloseSearch)
        compose.waitForIdle()
        assertThat(
            compose.onNodeWithTag(HomeTestTags.favorite(h.state.favorites.first())).getBoundsInRoot(),
        ).isEqualTo(before)
    }

    /** T-SET-06: with the master off, scrub, long-press and reorder make 0 haptic calls. */
    @Test
    fun tSet06_hapticsMasterOff() {
        val h =
            HomeHarness(
                apps,
                favorites = listOf(alpha.key, bravo.key),
                settings = Settings(onboardingDone = true, hapticsEnabled = false),
            )
        h.setContent(compose)
        compose.onNodeWithTag(HomeTestTags.favorite(h.state.favorites.first())).performTouchInput { longClick() }
        h.vm.onAction(HomeAction.CloseModal)
        compose.waitForIdle()
        compose.onNodeWithTag(HomeTestTags.HOME_INDEX).performTouchInput {
            down(topCenter)
            repeat(20) { moveBy(Offset(0f, height / 20f)) }
            up()
        }
        compose.waitForIdle()
        h.vm.onAction(HomeAction.CloseList)
        h.vm.onAction(HomeAction.EditFavorites)
        compose.waitForIdle()
        val first = h.state.favEdit.first()
        compose.onNodeWithTag(FavEditTestTags.handle(first), useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(5) { moveBy(Offset(0f, 40f)) }
            up()
        }
        compose.waitForIdle()
        assertThat(h.haptics.calls).isEmpty()
    }

    /** T-A11Y-01: rows and favourites carry every sheet action; invoking them works. */
    @Test
    fun tA11y01_customActions() {
        val h = HomeHarness(apps, favorites = listOf(user.key))
        h.setContent(compose)
        val favNode = compose.onNodeWithTag(HomeTestTags.favorite(h.state.favorites.single()))
        assertThat(favNode.customActionLabels())
            .containsExactly(
                "Remove from favourites",
                "Rename",
                "Hide from list",
                "Change icon",
                "App info",
                "Uninstall",
            ).inOrder()
        favNode.invokeCustomAction(compose, "App info")
        assertThat(h.launcher.appInfoRequests).containsExactly(user.key)
        favNode.invokeCustomAction(compose, "Uninstall")
        assertThat(h.launcher.uninstallRequests).containsExactly(user.key)
        favNode.invokeCustomAction(compose, "Rename")
        compose.onNodeWithTag(SheetTestTags.RENAME_FIELD).assertExists()
        h.vm.onAction(HomeAction.CloseModal)
        compose.waitForIdle()

        h.vm.onAction(HomeAction.OpenList)
        compose.waitForIdle()
        val clockRow =
            h.state.list!!
                .allApps
                .first { it.key == system.key }
        val row = compose.onNodeWithTag(AppListTestTags.row(clockRow))
        assertThat(row.customActionLabels())
            .containsExactly("Add to favourites", "Rename", "Hide from list", "Change icon", "App info")
        row.invokeCustomAction(compose, "Add to favourites")
        assertThat(h.favoritesRepo.state.value).containsExactly(user.key, system.key).inOrder()
        row.invokeCustomAction(compose, "Hide from list")
        assertThat(
            h.overrides.state.value[system.key]
                ?.hidden,
        ).isTrue()
        // The rest page is hidden from accessibility while the list covers it: return to rest first.
        h.vm.onAction(HomeAction.HomePressed)
        compose.waitForIdle()
        favNode.invokeCustomAction(compose, "Change icon") // M5: explains instead of doing nothing
    }

    /** T-A11Y-02: with the index `never`, "Jump to letter…" → R moves focus to the first R app; empty letters skipped. */
    @Test
    fun tA11y02_jumpToLetter() {
        val base = StrataDefault.resolved(systemDark = false)
        val h = HomeHarness(apps)
        h.setContent(compose, base.copy(alphabetIndex = base.alphabetIndex.copy(visibility = IndexVisibility.NEVER)))
        h.vm.onAction(HomeAction.OpenList)
        compose.waitForIdle()
        compose.onNodeWithTag(AlphabetIndexTestTags.INDEX).assertDoesNotExist()
        compose.onNode(hasTestTag(AppListTestTags.LIST)).assertExists()
        val lazy =
            compose.onNode(
                androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                    androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex,
                ),
            )
        lazy.invokeCustomAction(compose, "Jump to letter…")
        compose.onNodeWithTag(SheetTestTags.LETTER_GRID).assertExists()
        compose.onNodeWithTag(SheetTestTags.letter("Q")).assertDoesNotExist()
        compose.onNodeWithTag(SheetTestTags.letter("R")).performClick()
        compose.waitForIdle()
        val firstR =
            h.state.list!!
                .sections
                .first { it.label == "R" }
                .apps
                .first()
        compose.onNodeWithTag(AppListTestTags.row(firstR)).assertIsFocused()
    }

    /** T-A11Y-03: section headers are headings; index letters are buttons "Jump to R, 2 apps". */
    @Test
    fun tA11y03_headingsAndIndexButtons() {
        val h = HomeHarness(apps)
        h.setContent(compose)
        h.vm.onAction(HomeAction.OpenList)
        compose.waitForIdle()
        val header = compose.onNodeWithTag(AppListTestTags.header("R")).fetchSemanticsNode()
        assertThat(header.config.contains(SemanticsProperties.Heading)).isTrue()
        val letters = compose.onAllNodes(hasTestTag(AlphabetIndexTestTags.letter("R")), useUnmergedTree = true)
        val description =
            letters.fetchSemanticsNodes().flatMap {
                it.config.getOrElse(
                    SemanticsProperties.ContentDescription,
                ) { emptyList() }
            }
        assertThat(description).contains("Jump to R, 2 apps")
    }

    /** T-LIFE-05 (C half): the open sheet closes with a snackbar when its app is removed. */
    @Test
    fun tLife05_sheetClosesWhenAppRemoved() {
        val h = HomeHarness(apps)
        h.setContent(compose)
        h.vm.onAction(
            HomeAction.OpenSheet(
                h.state.list!!
                    .allApps
                    .first { it.key == user.key },
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTestTags.SHEET).assertExists()
        h.catalog.catalog.value = Catalog(apps - user, fromSnapshot = false)
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTestTags.SHEET).assertDoesNotExist()
        compose.onNodeWithText("This app was removed").assertExists()
    }

    /** T-LIFE-07 / FR-LIST-08 (C half): a suspended app stays, greyed, with the "Suspended" tag; a tap still launches. */
    @Test
    fun tLife07_suspendedRow() {
        val suspended = app("Radar", pkg = "fixture.radar", flags = AppFlags.SUSPENDED)
        val h = HomeHarness(apps + suspended)
        h.setContent(compose)
        h.vm.onAction(HomeAction.OpenList)
        compose.waitForIdle()
        val row =
            h.state.list!!
                .allApps
                .first { it.key == suspended.key }
        compose.onNode(hasTestTag(AppListTestTags.row(row))).performScrollTo()
        compose.onNodeWithTag(AppListTestTags.row(row)).assertExists()
        compose.onNodeWithText("Suspended", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(AppListTestTags.row(row)).performClick()
        assertThat(h.launcher.launches.map { it.first }).containsExactly(suspended.key)
    }

    /** T-HOME-09 (C half): one Home press from sheet, search, list or scrolled favourites returns to rest. */
    @Test
    fun tHome09_homePressResets() {
        val h = HomeHarness(apps, favorites = listOf(alpha.key))
        h.setContent(compose)
        h.vm.onAction(HomeAction.OpenSheet(h.state.favorites.single()))
        h.vm.onAction(HomeAction.HomePressed)
        compose.waitForIdle()
        assertThat(h.state.modal).isEqualTo(HomeModal.None)
        h.vm.onAction(HomeAction.OpenList)
        h.vm.onAction(HomeAction.OpenSearch("ma"))
        h.vm.onAction(HomeAction.HomePressed)
        compose.waitForIdle()
        assertThat(h.state.layer).isEqualTo(HomeLayer.REST)
        assertThat(h.state.search).isNull()
        h.vm.onAction(HomeAction.OpenList)
        h.vm.onAction(HomeAction.HomePressed)
        compose.waitForIdle()
        assertThat(h.state.layer).isEqualTo(HomeLayer.REST)
        compose.onNodeWithTag(AppListTestTags.LIST).assertDoesNotExist()
    }

    /** A jump is one-shot: after scrolling back up, closing and reopening the list does not jump to R again. */
    @Test
    fun listJumpIsConsumed() {
        val many = ('A'..'Z').flatMap { l -> (1..3).map { app("$l app $it", pkg = "fixture.${l.lowercaseChar()}$it") } }
        val h = HomeHarness(many)
        h.setContent(compose)
        h.vm.onAction(
            HomeAction.JumpToLetter(
                h.state.list!!
                    .index
                    .indexOfFirst { it.label == "R" },
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithTag(AppListTestTags.header("R")).assertExists()
        assertThat(h.state.listJump).isNull()
        val lazy =
            compose.onNode(
                androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                    androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex,
                ),
            )
        lazy.performScrollToNode(hasTestTag(AppListTestTags.header("A")))
        h.vm.onAction(HomeAction.CloseList)
        compose.waitForIdle()
        h.vm.onAction(HomeAction.OpenList)
        compose.waitForIdle()
        compose.onNodeWithTag(AppListTestTags.header("A")).assertExists()
        compose.onNodeWithTag(AppListTestTags.header("R")).assertDoesNotExist()
    }

    /** T-HOME-10 (C half): with the setting on, Home at rest opens search; off, nothing happens. */
    @Test
    fun tHome10_homePressOpensSearch() {
        val h = HomeHarness(apps, settings = Settings(onboardingDone = true, homePressOpensSearch = true))
        h.setContent(compose)
        h.vm.onAction(HomeAction.HomePressed)
        compose.waitForIdle()
        compose.onNodeWithTag(SEARCH_STUB).assertExists()
        h.vm.onAction(HomeAction.HomePressed)
        h.settingsRepo.set(Settings(onboardingDone = true))
        h.vm.onAction(HomeAction.HomePressed)
        compose.waitForIdle()
        assertThat(h.state.layer).isEqualTo(HomeLayer.REST)
    }
}
