package app.strata.launcher.feature.home

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.platformapi.apps.AppShortcut
import app.strata.launcher.core.platformapi.apps.Profile
import app.strata.launcher.core.platformapi.apps.QuietModeResult
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.testing.fakes.FakeProfileSource
import app.strata.launcher.testing.fakes.FakeShortcutSource
import app.strata.launcher.testing.fixtures.AppFixtures.app
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Work profile (FR-APPS-09), private space (FR-APPS-10) and app shortcuts (FR-APPS-08) over fakes (Robolectric
 * Compose, levels R/C). The managed-profile and private-space device halves are written as device tests and manual
 * checks (GMD cannot create those profiles).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ProfilesAndShortcutsTest {
    @get:Rule
    val compose = createComposeRule()

    private val alpha = app("Alpha")
    private val maps = app("Maps")
    private val mail = app("Mail", serial = WORK)
    private val bank = app("Bank", serial = PRIVATE, kind = ProfileKind.PRIVATE)
    private val apps = listOf(alpha, maps, mail, bank)

    private fun profiles(
        workPaused: Boolean = false,
        privateLocked: Boolean = false,
    ) = FakeProfileSource(
        listOf(
            Profile(0, ProfileKind.MAIN, quietMode = false, available = true),
            Profile(WORK, ProfileKind.WORK, quietMode = workPaused, available = !workPaused),
            Profile(PRIVATE, ProfileKind.PRIVATE, quietMode = privateLocked, available = !privateLocked),
        ),
    )

    private fun harness(
        profiles: FakeProfileSource = profiles(),
        shortcuts: FakeShortcutSource? = null,
    ) = HomeHarness(apps, profiles = profiles, shortcuts = shortcuts).also {
        it.setContent(compose)
        it.vm.onAction(HomeAction.OpenList)
        compose.waitForIdle()
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun countTag(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    /**
     * T-APPS-14 (R/C): work apps are badged and reachable through the "Work" filter; paused → "Paused" tag; a tap
     * offers "Turn on work apps?", which resumes the profile; the rows become active without a restart.
     */
    @Test
    fun tApps14_pausedWorkApp_offersResume() {
        val p = profiles(workPaused = true)
        val h = harness(p)
        compose.onNodeWithTag(AppListTestTags.WORK_BAR).assertExists()
        compose.onNodeWithText("Paused").assertExists()
        compose.onNodeWithText("Mail").performClick()
        compose.onNodeWithTag(SheetTestTags.RESUME_WORK).assertExists()
        assertThat(h.launcher.launches).isEmpty()
        compose.onNodeWithTag(SheetTestTags.RESUME_WORK_CONFIRM).performClick()
        compose.waitForIdle()
        assertThat(p.quietRequests).containsExactly(WORK to false)
        assertThat(count("Paused")).isEqualTo(0)
        compose.onNodeWithText("Mail").performClick()
        assertThat(h.launcher.launches.map { it.first }).containsExactly(mail.key)
    }

    /** FR-APPS-09: the "Work" filter shows work apps only; the switch pauses the profile. */
    @Test
    fun workFilterAndSwitch() {
        val p = profiles()
        harness(p)
        compose.onNodeWithTag(AppListTestTags.WORK_FILTER_WORK).performClick()
        compose.waitForIdle()
        assertThat(count("Mail")).isEqualTo(1)
        assertThat(count("Alpha")).isEqualTo(0)
        compose.onNodeWithTag(AppListTestTags.WORK_FILTER_ALL).performClick()
        compose.waitForIdle()
        assertThat(count("Alpha")).isEqualTo(1)
        compose.onNodeWithTag(AppListTestTags.WORK_SWITCH).performClick()
        compose.waitForIdle()
        assertThat(p.quietRequests).containsExactly(WORK to true)
        compose.onNodeWithText("Paused").assertExists()
    }

    /** T-LIFE-08 (R): pausing from quick settings dims the rows with "Paused" at once; resuming clears it; no restart. */
    @Test
    fun tLife08_pauseWhileShown() {
        val p = profiles()
        val h = harness(p)
        val vm = h.vm
        assertThat(count("Paused")).isEqualTo(0)
        p.setQuiet(WORK, true) // the profile broadcast
        compose.waitForIdle()
        compose.onNodeWithText("Paused").assertExists()
        p.setQuiet(WORK, false)
        compose.waitForIdle()
        assertThat(count("Paused")).isEqualTo(0)
        assertThat(h.vm).isSameInstanceAs(vm)
    }

    /** A refused request (not the default launcher) is explained, not silent. */
    @Test
    fun refusedQuietModeRequestIsExplained() {
        val p = profiles().also { it.quietResult = QuietModeResult.NOT_ALLOWED }
        harness(p)
        compose.onNodeWithTag(AppListTestTags.WORK_SWITCH).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Only the default home app can do this. Set Strata as default first.").assertExists()
    }

    /**
     * T-APPS-15 (R/C): a separate container at the list end; Lock leaves no rows and shows the "Unlock" entry; Unlock
     * shows the rows again; Hide removes the container.
     */
    @Test
    fun tApps15_privateContainer() {
        val p = profiles()
        val h = harness(p)
        compose.onNodeWithTag(AppListTestTags.PRIVATE_HEADER).assertExists()
        compose
            .onNodeWithTag(
                AppListTestTags.privateRow(
                    h.state.privateSpace!!
                        .apps
                        .single(),
                ),
            ).assertExists()
        assertThat(
            h.state.list!!
                .sections
                .flatMap { it.apps }
                .map { it.displayLabel },
        ).doesNotContain("Bank")

        compose.onNodeWithTag(AppListTestTags.PRIVATE_LOCK).performClick()
        compose.waitForIdle()
        assertThat(p.quietRequests).containsExactly(PRIVATE to true)
        assertThat(count("Bank")).isEqualTo(0)
        compose.onNodeWithTag(AppListTestTags.PRIVATE_UNLOCK).assertExists()

        compose.onNodeWithTag(AppListTestTags.PRIVATE_UNLOCK).performClick()
        compose.waitForIdle()
        assertThat(count("Bank")).isEqualTo(1)

        compose.onNodeWithTag(AppListTestTags.PRIVATE_HIDE).performClick()
        compose.waitForIdle()
        assertThat(h.settingsRepo.value.privateSpaceContainerHidden).isTrue()
        assertThat(countTag(AppListTestTags.PRIVATE_HEADER)).isEqualTo(0)
        assertThat(count("Bank")).isEqualTo(0)
    }

    /** T-APPS-15: with the entry point hidden (API 36 config), a locked space shows nothing at all. */
    @Test
    fun tApps15_lockedWithHiddenEntry_showsNothing() {
        val p = profiles(privateLocked = true).also { it.entryHidden += PRIVATE }
        harness(p)
        assertThat(countTag(AppListTestTags.PRIVATE_HEADER)).isEqualTo(0)
        assertThat(countTag(AppListTestTags.PRIVATE_UNLOCK)).isEqualTo(0)
    }

    /**
     * T-APPS-16 (R half): a private app's sheet has Rename disabled with the explanation and no favourite, hide or icon
     * action; actions sent anyway store nothing, and a launch is not logged.
     */
    @Test
    fun tApps16_privateAppNeverStored() {
        val h = harness()
        compose.onNodeWithText("Bank").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTestTags.RENAME).assertIsNotEnabled()
        compose.onNodeWithTag(SheetTestTags.PRIVATE_NOTE).assertExists()
        assertThat(countTag(SheetTestTags.FAVORITE)).isEqualTo(0)
        assertThat(countTag(SheetTestTags.HIDE)).isEqualTo(0)
        assertThat(countTag(SheetTestTags.CHANGE_ICON)).isEqualTo(0)
        compose.onNodeWithTag(SheetTestTags.APP_INFO).assertExists()
        val labels =
            appActions(
                h.state.privateSpace!!
                    .apps
                    .single(),
                favorite = false,
                actionLabels,
            ) {}.map { it.label }
        assertThat(labels).containsExactly("App info", "Uninstall")

        h.vm.onAction(HomeAction.ToggleFavorite(bank.key))
        h.vm.onAction(HomeAction.SetHidden(bank.key, true))
        h.vm.onAction(HomeAction.Rename(bank.key, "Renamed"))
        h.vm.onAction(HomeAction.CloseModal)
        compose.waitForIdle()
        compose.onNodeWithText("Bank").performClick()
        compose.waitForIdle()
        assertThat(h.favoritesRepo.state.value).isEmpty()
        assertThat(h.overrides.overrides.value).isEmpty()
        assertThat(h.launchLog.events).isEmpty()
        assertThat(h.launcher.launches.map { it.first }).containsExactly(bank.key)
    }

    /** T-APPS-12 (R/C): as default, the sheet lists the app's shortcuts and a tap starts one; without the role, none. */
    @Test
    fun tApps12_shortcutsInSheet() {
        val shortcuts = FakeShortcutSource(host = true, byKey = mutableMapOf(maps.key to threeShortcuts()))
        harness(shortcuts = shortcuts)
        compose.onNodeWithText("Maps").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTestTags.SHORTCUTS).assertExists()
        listOf("home", "work", "recent").forEach { compose.onNodeWithTag(SheetTestTags.shortcut(it)).assertExists() }
        compose.onNodeWithTag(SheetTestTags.shortcut("work")).performClick()
        compose.waitForIdle()
        assertThat(shortcuts.launched.map { it.id }).containsExactly("work")
        assertThat(countTag(SheetTestTags.SHEET)).isEqualTo(0)
    }

    @Test
    fun tApps12_notDefault_noShortcutSection() {
        val shortcuts = FakeShortcutSource(host = false, byKey = mutableMapOf(maps.key to threeShortcuts()))
        harness(shortcuts = shortcuts)
        compose.onNodeWithText("Maps").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTestTags.SHEET).assertExists()
        assertThat(countTag(SheetTestTags.SHORTCUTS)).isEqualTo(0)
    }

    /** T-LIFE-10 (shortcut half): the default home is lost while the sheet is open → the section goes on resume. */
    @Test
    fun tLife10_shortcutSectionHiddenAfterLosingDefault() {
        val shortcuts = FakeShortcutSource(host = true, byKey = mutableMapOf(maps.key to threeShortcuts()))
        val h = harness(shortcuts = shortcuts)
        compose.onNodeWithText("Maps").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTestTags.SHORTCUTS).assertExists()
        shortcuts.host = false
        h.vm.onAction(HomeAction.RefreshRole)
        compose.waitForIdle()
        assertThat(countTag(SheetTestTags.SHORTCUTS)).isEqualTo(0)
    }

    /** INTERACTION_SPEC §8: a shortcut removed by its app → snackbar. */
    @Test
    fun removedShortcutShowsSnackbar() {
        val shortcuts =
            FakeShortcutSource(host = true, byKey = mutableMapOf(maps.key to threeShortcuts())).also {
                it.removed +=
                    "home"
            }
        harness(shortcuts = shortcuts)
        compose.onNodeWithText("Maps").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTestTags.shortcut("home")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("This shortcut was removed by its app").assertExists()
    }

    private fun threeShortcuts() =
        listOf(
            AppShortcut("home", maps.packageName, 0, "Home", dynamic = false, rank = 0),
            AppShortcut("work", maps.packageName, 0, "Work", dynamic = false, rank = 1),
            AppShortcut("recent", maps.packageName, 0, "Last place", dynamic = true, rank = 0),
        )

    private companion object {
        const val WORK = 10L
        const val PRIVATE = 99L
    }

    private val actionLabels =
        ActionLabels("Fav", "Unfav", "Rename", "Hide", "Unhide", "Icon", "App info", "Uninstall", "Uninstall updates")
}
