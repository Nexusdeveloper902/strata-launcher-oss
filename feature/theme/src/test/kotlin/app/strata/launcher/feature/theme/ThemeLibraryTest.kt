package app.strata.launcher.feature.theme

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.importing.ConflictChoice
import app.strata.launcher.core.theme.parse.ThemeParser
import app.strata.launcher.core.ui.StrataDefaultTheme
import app.strata.launcher.testing.fakes.FakeSystemIntents
import app.strata.launcher.testing.fixtures.BundleFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Theme library (Robolectric; the real repository over in-memory stores): T-THEME-35 (C half: list, apply,
 * duplicate, delete + undo within 5 s, hide a built-in, delete the active theme), T-THEME-42 (wallpaper confirmation
 * and hand-off with FakeSystemIntents) and the R half of T-THEME-30 (export via SAF, share via the FileProvider URI).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ThemeLibraryTest {
    @get:Rule
    val compose = createComposeRule()

    private val h = ThemeHarness()
    private val undoScope = TestScope(StandardTestDispatcher())

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(intents: FakeSystemIntents = h.intents) =
        ThemeLibraryViewModel(h.repo, h.repo, h.documents, h.shared, intents, undoScope)

    private fun show(vm: ThemeLibraryViewModel) {
        compose.setContent {
            StrataDefaultTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                ThemeLibraryScreen(state, vm::onAction)
            }
        }
        compose.waitForIdle()
    }

    private fun import(bytes: ByteArray): String =
        runBlocking {
            val p = (h.repo.prepareImport(bytes) as Outcome.Ok).value
            (h.repo.commitImport(p.token, ConflictChoice.KEEP_BOTH, apply = false) as Outcome.Ok).value.id
        }

    private fun menu(
        id: String,
        item: String,
    ) {
        compose.onNodeWithTag(ThemeTestTags.menu(id)).performClick()
        compose.onNodeWithTag(ThemeTestTags.menuItem(id, item)).performClick()
        compose.waitForIdle()
    }

    private val idA = "aaaaaaaa-0000-4000-8000-000000000001"

    // ---- T-THEME-35 ---------------------------------------------------------------------------------

    @Test
    fun tTheme35_listsBuiltInsAndStoredThemesAndApplies() {
        import(ThemeHarness.theme(idA, "Mine"))
        val vm = viewModel()
        show(vm)
        compose.onNodeWithText("Strata Default").assertExists()
        compose.onNodeWithText("Paper").assertExists()
        compose.onNodeWithTag(ThemeTestTags.row(idA)).performClick()
        compose.waitForIdle()
        assertThat(h.repo.state.value.activeId).isEqualTo(idA)
    }

    @Test
    fun tTheme35_fiveHundredThemesScrollAndApply() {
        repeat(
            500,
        ) { i -> import(ThemeHarness.theme("%08d-0000-4000-8000-000000000000".format(i), "Theme %03d".format(i))) }
        val vm = viewModel()
        show(vm)
        assertThat(vm.state.value.rows.size).isEqualTo(506) // Strata Default + 5 built-ins + 500
        val last = "00000499-0000-4000-8000-000000000000"
        compose
            .onAllNodesWithTag(ThemeTestTags.row(StrataDefault.ID))
            .assertCountEquals(1)
        compose.onNode(hasScrollableList()).performScrollToNode(hasTestTag(ThemeTestTags.row(last)))
        compose.onNodeWithTag(ThemeTestTags.row(last)).performClick()
        compose.waitForIdle()
        assertThat(h.repo.state.value.activeId).isEqualTo(last)
    }

    private fun hasScrollableList() =
        androidx.compose.ui.test
            .hasScrollToNodeAction()

    @Test
    fun tTheme35_duplicateAndEditBuiltInMakeCopies() {
        val vm = viewModel()
        show(vm)
        menu(ThemeHarness.PAPER, "duplicate")
        compose.onNodeWithText("Paper (copy)").assertExists()
        assertThat(runBlocking { h.repo.editableCopy(ThemeHarness.PAPER) }).isNotEqualTo(ThemeHarness.PAPER)
        assertThat(
            vm.state.value.rows
                .map { it.name },
        ).contains("Paper (copy) (2)")
    }

    @Test
    fun tTheme35_deleteIsUndoableForFiveSeconds() {
        import(ThemeHarness.theme(idA, "Mine"))
        val vm = viewModel()
        show(vm)
        menu(idA, "delete")
        compose.onNodeWithTag(ThemeTestTags.UNDO_BAR).assertExists()
        compose.onAllNodesWithTag(ThemeTestTags.row(idA)).assertCountEquals(0)
        undoScope.advanceTimeBy(4_900)
        undoScope.runCurrent()
        compose.onNodeWithTag(ThemeTestTags.UNDO).performClick()
        compose.waitForIdle()
        undoScope.advanceTimeBy(10_000)
        undoScope.runCurrent()
        compose.onNodeWithTag(ThemeTestTags.row(idA)).assertExists()
        assertThat(h.files.files.keys).contains("user/$idA.strata-theme.json")

        // Without undo the file goes after 5 s.
        menu(idA, "delete")
        undoScope.advanceTimeBy(5_001)
        undoScope.runCurrent()
        compose.waitForIdle()
        compose.onAllNodesWithTag(ThemeTestTags.UNDO_BAR).assertCountEquals(0)
        assertThat(h.files.files.keys).doesNotContain("user/$idA.strata-theme.json")
        assertThat(
            vm.state.value.rows
                .none { it.id == idA },
        ).isTrue()
    }

    @Test
    fun tTheme35_deletingTheActiveThemeSwitchesToLastKnownGoodFirst() {
        val idB = "bbbbbbbb-0000-4000-8000-000000000002"
        import(ThemeHarness.theme(idA, "Good"))
        import(ThemeHarness.theme(idB, "Current"))
        h.repo.apply(idA)
        h.repo.onFirstFrame(h.repo.state.value.applyGeneration)
        h.repo.onStableRender()
        h.repo.apply(idB)
        val vm = viewModel()
        show(vm)
        menu(idB, "delete")
        // Switched before the undo window ends.
        assertThat(h.repo.state.value.activeId).isEqualTo(idA)
        compose.onNodeWithTag(ThemeTestTags.UNDO).performClick()
        compose.waitForIdle()
        assertThat(h.repo.state.value.activeId).isEqualTo(idB)
    }

    @Test
    fun tTheme35_hideABuiltIn() {
        val vm = viewModel()
        show(vm)
        menu(ThemeHarness.PAPER, "hide")
        compose.onAllNodesWithTag(ThemeTestTags.row(ThemeHarness.PAPER)).assertCountEquals(0)
        compose.onNodeWithTag(ThemeTestTags.SHOW_HIDDEN).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ThemeTestTags.row(ThemeHarness.PAPER)).assertExists()
        menu(ThemeHarness.PAPER, "unhide")
        assertThat(vm.state.value.hiddenCount).isEqualTo(0)
        // Built-ins and Strata Default cannot be deleted.
        compose.onNodeWithTag(ThemeTestTags.menu(StrataDefault.ID)).performClick()
        compose.onAllNodesWithTag(ThemeTestTags.menuItem(StrataDefault.ID, "delete")).assertCountEquals(0)
        compose.onAllNodesWithTag(ThemeTestTags.menuItem(StrataDefault.ID, "hide")).assertCountEquals(0)
    }

    // ---- T-THEME-42 ---------------------------------------------------------------------------------

    private fun wallpaperTheme(): String =
        import(
            BundleFixtures.bundle(font = null, image = BundleFixtures.webpHeader(64, 32)),
        )

    @Test
    fun tTheme42_declineStartsNothingAcceptHandsOffOnce() {
        val id = wallpaperTheme()
        val vm = viewModel()
        show(vm)
        compose.onNodeWithTag(ThemeTestTags.row(id)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Also set this theme's wallpaper?").assertExists()
        compose.onNodeWithTag(ThemeTestTags.WALLPAPER_NO).performClick()
        compose.waitForIdle()
        assertThat(h.intents.wallpaperHandoffs).isEmpty()
        assertThat(h.shared.published).isEmpty()

        compose.onNodeWithTag(ThemeTestTags.row(id)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ThemeTestTags.WALLPAPER_YES).performClick()
        compose.waitForIdle()
        assertThat(h.intents.wallpaperHandoffs).hasSize(1)
        val (kind, uri) = h.intents.wallpaperHandoffs.single()
        assertThat(kind).isEqualTo(FakeSystemIntents.CROP)
        assertThat(uri).startsWith("content://app.strata.launcher.files/share/")
        assertThat(uri).endsWith(".webp")
        assertThat(h.shared.published.getValue(uri)).isEqualTo(BundleFixtures.webpHeader(64, 32))
    }

    @Test
    fun tTheme42_noCropHandlerFallsBackToAttachDataThenMessage() {
        val id = wallpaperTheme()
        val intents = FakeSystemIntents().apply { cropHandler = false }
        val vm = viewModel(intents)
        val messages = mutableListOf<LibraryEffect>()
        show(vm)
        compose.onNodeWithTag(ThemeTestTags.row(id)).performClick()
        compose.onNodeWithTag(ThemeTestTags.WALLPAPER_YES).performClick()
        compose.waitForIdle()
        assertThat(intents.wallpaperHandoffs.single().first).isEqualTo(FakeSystemIntents.ATTACH)

        intents.attachHandler = false
        compose.onNodeWithTag(ThemeTestTags.row(id)).performClick()
        compose.onNodeWithTag(ThemeTestTags.WALLPAPER_YES).performClick()
        compose.waitForIdle()
        runBlocking { messages += vm.effects.first() }
        assertThat(messages.single()).isEqualTo(LibraryEffect.Message(LibraryMessage.NO_WALLPAPER_APP))
        assertThat(intents.wallpaperHandoffs).hasSize(1)
    }

    @Test
    fun themeWithoutWallpaperImageAsksNothing() {
        import(ThemeHarness.theme(idA, "Plain"))
        val vm = viewModel()
        show(vm)
        compose.onNodeWithTag(ThemeTestTags.row(idA)).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag(ThemeTestTags.WALLPAPER_DIALOG).assertCountEquals(0)
    }

    // ---- T-THEME-30 (R half) ----------------------------------------------------------------------

    @Test
    fun tTheme30_exportToADocumentValidates() {
        import(ThemeHarness.theme(idA, "Export me", ""","x-tool":{"k":1}"""))
        val vm = viewModel()
        show(vm)
        menu(idA, "export")
        val effect = runBlocking { vm.effects.first() } as LibraryEffect.CreateDocument
        assertThat(effect.suggestedName).isEqualTo("Export me.strata-theme.json")
        assertThat(effect.mimeType).isEqualTo("application/json")
        vm.onAction(LibraryAction.ExportTo("content://docs/doc/1"))
        val written = h.documents.documents.getValue("content://docs/doc/1")
        val parsed = (ThemeParser().parse(written) as Outcome.Ok).value
        assertThat(parsed.isStrictlyValid).isTrue()
        assertThat(parsed.document.id).isEqualTo(idA)
        assertThat(parsed.tree["x-tool"]).isNotNull()
    }

    @Test
    fun tTheme30_exportWithWallpaperOffersTheBundle() {
        val id = wallpaperTheme()
        val vm = viewModel()
        show(vm)
        menu(id, "export")
        compose.onNodeWithText("Only share fonts whose licence allows it.").assertExists()
        compose.onNodeWithTag(ThemeTestTags.EXPORT_BUNDLE).performClick()
        compose.waitForIdle()
        val effect = runBlocking { vm.effects.first() } as LibraryEffect.CreateDocument
        assertThat(effect.mimeType).isEqualTo("application/zip")
        assertThat(effect.suggestedName).endsWith(".strata-theme.zip")
    }

    @Test
    fun tTheme30_shareGivesTheReceiverAContentUri() {
        import(ThemeHarness.theme(idA, "Shared"))
        val vm = viewModel()
        show(vm)
        menu(idA, "share")
        val (uri, mime) = h.intents.shares.single()
        assertThat(uri).startsWith("content://app.strata.launcher.files/share/")
        assertThat(mime).isEqualTo("application/json")
        assertThat(
            (ThemeParser().parse(h.shared.published.getValue(uri)) as Outcome.Ok).value.document.id,
        ).isEqualTo(idA)
    }

    @Test
    fun moreButtonHasALabel() {
        val vm = viewModel()
        show(vm)
        compose.onNodeWithContentDescription("More options for Paper").assertExists()
    }

    // ---- review fixes: the 5 s delete window -----------------------------------------------------------

    private fun passWindow() {
        undoScope.advanceTimeBy(5_001)
        undoScope.runCurrent()
        compose.waitForIdle()
    }

    private val userFile get() = "user/$idA.strata-theme.json"

    @Test
    fun reimportInsideTheWindowIsNotDeleted() {
        import(ThemeHarness.theme(idA, "Mine"))
        val vm = viewModel()
        show(vm)
        menu(idA, "delete")
        // Same id, new content, imported again (Replace) before the window ends.
        runBlocking {
            val p = (h.repo.prepareImport(ThemeHarness.theme(idA, "Mine", ""","author":"again"""")) as Outcome.Ok).value
            h.repo.commitImport(p.token, ConflictChoice.REPLACE, apply = false)
        }
        passWindow()
        assertThat(h.files.files.keys).contains(userFile)
    }

    @Test
    fun applyInsideTheWindowIsNotDeleted() {
        import(ThemeHarness.theme(idA, "Mine"))
        val vm = viewModel()
        show(vm)
        menu(idA, "delete")
        h.repo.apply(idA)
        passWindow()
        assertThat(h.files.files.keys).contains(userFile)
        assertThat(h.repo.state.value.activeId).isEqualTo(idA)
    }

    @Test
    fun undoDoesNotOverrideAThemeChosenMeanwhile() {
        val idB = "bbbbbbbb-0000-4000-8000-000000000002"
        import(ThemeHarness.theme(idB, "Current"))
        h.repo.apply(idB)
        val vm = viewModel()
        show(vm)
        menu(idB, "delete") // switches to Strata Default at once
        assertThat(h.repo.state.value.activeId).isEqualTo(StrataDefault.ID)
        h.repo.apply(ThemeHarness.PAPER) // the user picks another theme inside the window
        compose.onNodeWithTag(ThemeTestTags.UNDO).performClick()
        compose.waitForIdle()
        assertThat(h.repo.state.value.activeId).isEqualTo(ThemeHarness.PAPER)
        compose.onNodeWithTag(ThemeTestTags.row(idB)).assertExists() // the delete itself is undone
    }

    @Test
    fun twoQuickDeletesBothCommit() {
        val idB = "bbbbbbbb-0000-4000-8000-000000000002"
        import(ThemeHarness.theme(idA, "One"))
        import(ThemeHarness.theme(idB, "Two"))
        val vm = viewModel()
        show(vm)
        // No frame in between: both actions are queued back to back.
        vm.onAction(LibraryAction.Delete(idA))
        vm.onAction(LibraryAction.Delete(idB))
        undoScope.runCurrent()
        compose.waitForIdle()
        assertThat(
            vm.state.value.pendingDelete
                ?.id,
        ).isEqualTo(idB)
        assertThat(h.files.files.keys).doesNotContain(userFile) // the first was committed when the second started
        passWindow()
        assertThat(
            h.files.files.keys
                .none { it.startsWith("user/") },
        ).isTrue()
    }

    @Test
    fun undoAfterTheCommitIsReported() {
        import(ThemeHarness.theme(idA, "Mine"))
        val vm = viewModel()
        show(vm)
        menu(idA, "delete")
        passWindow()
        vm.onAction(LibraryAction.UndoDelete)
        assertThat(runBlocking { vm.effects.first() }).isEqualTo(LibraryEffect.Message(LibraryMessage.UNDO_TOO_LATE))
        assertThat(h.files.files.keys).doesNotContain(userFile)
    }

    // ---- review fixes: export failures --------------------------------------------------------------

    @Test
    fun exportResultWithoutPreparedBytesIsReported() {
        val vm = viewModel() // e.g. recreated after process death while the picker was open
        show(vm)
        vm.onAction(LibraryAction.ExportTo("content://docs/doc/9"))
        assertThat(runBlocking { vm.effects.first() }).isEqualTo(LibraryEffect.Message(LibraryMessage.EXPORT_FAILED))
        vm.onAction(LibraryAction.ExportUnavailable)
        assertThat(runBlocking { vm.effects.first() }).isEqualTo(LibraryEffect.Message(LibraryMessage.EXPORT_FAILED))
    }
}
