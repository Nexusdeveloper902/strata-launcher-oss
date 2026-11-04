package app.strata.launcher.feature.theme

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.contrast.ContrastStatus
import app.strata.launcher.core.theme.importing.ImportConflict
import app.strata.launcher.core.theme.importing.ImportMessage
import app.strata.launcher.core.theme.resolve.WallpaperColorsInput
import app.strata.launcher.core.ui.StrataDefaultTheme
import app.strata.launcher.testing.fixtures.BundleFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Import summary (Robolectric Compose over the real repository): T-THEME-26 (static vs runtime badge), T-THEME-31
 * (C half), T-THEME-34 (C half: the four conflict rows), the R half of T-THEME-27 (every MIME label sniffs and imports,
 * nothing applied before confirmation) and of T-SEC-01 (non-content URIs refused unread).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ThemeImportTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private var h = ThemeHarness()

    private fun viewModel() = ThemeImportViewModel(h.repo, h.repo, h.documents, h.shared, h.intents)

    private fun open(
        bytes: ByteArray,
        uri: String = "content://provider/doc/${h.documents.documents.size}",
    ): ThemeImportViewModel {
        h.documents.documents[uri] = bytes
        val vm = viewModel()
        vm.load(uri)
        compose.setContent {
            StrataDefaultTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                ThemeImportScreen(state, vm::onAction, onClose = {})
            }
        }
        compose.waitForIdle()
        return vm
    }

    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag)
        // Screen buttons sit in a scrolling column; dialog buttons do not scroll.
        try {
            node.performScrollTo()
        } catch (e: AssertionError) {
            Unit
        }
        node.performClick()
        compose.waitForIdle()
    }

    private val idA = "aaaaaaaa-0000-4000-8000-000000000001"

    private fun storedIds() =
        runBlocking {
            h.repo
                .entries()
                .filter { !it.builtIn }
                .map { it.id }
        }

    // ---- T-THEME-27 (R half) / FR-THEME-21 --------------------------------------------------------

    @Test
    fun tTheme27_summaryFirstNothingAppliedUntilConfirmed() {
        val vm = open(ThemeHarness.theme(idA, "Shared theme"))
        compose.onNodeWithText("Shared theme").assertExists()
        assertThat(h.repo.state.value.activeId).isEqualTo(StrataDefault.ID)
        assertThat(storedIds()).isEmpty()
        click(ThemeTestTags.IMPORT_ADD_APPLY)
        assertThat((vm.state.value as ImportUiState.Done).commit.applied).isTrue()
        assertThat(h.repo.state.value.activeId).isEqualTo(idA)
    }

    @Test
    fun tTheme27_addDoesNotApply() {
        open(ThemeHarness.theme(idA, "Only add"))
        click(ThemeTestTags.IMPORT_ADD)
        assertThat(storedIds()).containsExactly(idA)
        assertThat(h.repo.state.value.activeId).isEqualTo(StrataDefault.ID)
    }

    @Test
    fun tTheme27_bundleAndJsonAreSniffedWhateverTheLabel() {
        // The fake resolver has no MIME types at all: only the bytes decide (THEME_SYSTEM §3).
        open(BundleFixtures.bundle(), uri = "content://provider/doc/blob.bin")
        compose.onNodeWithText("Bundled").assertExists()
        compose.onNodeWithText("Includes its own fonts or wallpaper").assertExists()
        click(ThemeTestTags.IMPORT_ADD)
        assertThat(
            h.files.files.keys
                .count { it.startsWith("fonts/") },
        ).isEqualTo(1)
        assertThat(
            h.files.files.keys
                .count { it.startsWith("images/") },
        ).isEqualTo(1)
    }

    @Test
    fun notAThemeShowsTheMessage() {
        open("just some text".toByteArray())
        compose.onNodeWithText("This file isn't a Strata theme.").assertExists()
    }

    @Test
    fun damagedJsonShowsLineAndColumn() {
        open("{\n  \"schemaVersion\": 1,\n  \"id\": oops\n}".toByteArray())
        compose.onNodeWithText("The file is damaged (line 3, column 9).").assertExists()
    }

    // ---- T-SEC-01 (R half) ------------------------------------------------------------------------

    @Test
    fun tSec01_nonContentUrisAreRefusedUnread() {
        for (uri in listOf("file:///sdcard/x.json", "http://example.invalid/x.json")) {
            h.documents.documents[uri] = ThemeHarness.theme(idA, "X")
            val vm = viewModel()
            vm.load(uri)
            assertThat(vm.state.value).isEqualTo(ImportUiState.Failed(ImportMessage.UNSUPPORTED_SOURCE))
        }
        assertThat(h.documents.opened).isEmpty()
        assertThat(storedIds()).isEmpty()
    }

    // ---- T-THEME-31 (C half) ------------------------------------------------------------------------

    @Test
    fun tTheme31_newerMajorShowsTheMessageAndWritesNothing() {
        val before =
            h.files.files.keys
                .toSet()
        open("""{"schemaVersion":2,"id":"$idA","name":"Future"}""".toByteArray())
        compose
            .onNodeWithText("This theme was made with a newer version of Strata Launcher. Update the app to use it.")
            .assertExists()
        assertThat(h.files.files.keys).isEqualTo(before)
    }

    @Test
    fun newerMinorListsKeptSettings() {
        open("""{"schemaVersion":1,"schemaMinor":5,"id":"$idA","name":"Next","futureA":1,"futureB":2}""".toByteArray())
        compose.onNodeWithText("2 unknown settings from a newer version will be kept").assertExists()
    }

    // ---- T-THEME-26 ---------------------------------------------------------------------------------

    /** Text on a transparent window: unreadable on some wallpapers, readable on the current (white) one. */
    private val runtimeOnly =
        ThemeHarness.theme(
            idA,
            "Runtime",
            """,
            "colors":{"mode":"light","light":{"textPrimary":"#000000","textSecondary":"#333333","accent":"#0000AA"}},
            "wallpaper":{"show":true,"dim":0.0,"backgroundOpacity":0.0}""",
        )

    @Test
    fun tTheme26_runtimeButNotStaticSaysCurrentWallpaper() {
        h = ThemeHarness(wallpaper = WallpaperColorsInput(primary = 0xFFFFFFFF.toInt()))
        val vm = open(runtimeOnly)
        val preview = (vm.state.value as ImportUiState.Summary).preview
        assertThat(preview.contrast).isEqualTo(ContrastStatus.AA_CURRENT_WALLPAPER)
        compose.onNodeWithText("Readable on current wallpaper").assertExists()
        compose.onAllNodesWithText("Readable on any wallpaper").assertCountEquals(0)
    }

    @Test
    fun tTheme26_staticPassSaysAnyWallpaperAndFailureSaysLow() {
        open(ThemeHarness.theme(idA, "Plain"))
        compose.onNodeWithText("Readable on any wallpaper").assertExists()
        h = ThemeHarness(wallpaper = WallpaperColorsInput(primary = 0xFF000000.toInt()))
        val vm = viewModel()
        h.documents.documents["content://p/low"] = runtimeOnly
        vm.load("content://p/low")
        assertThat((vm.state.value as ImportUiState.Summary).preview.contrast).isEqualTo(ContrastStatus.LOW_CONTRAST)
    }

    // ---- T-THEME-34 (C half) ------------------------------------------------------------------------

    private fun store(bytes: ByteArray) {
        val vm = viewModel()
        h.documents.documents["content://seed/${h.documents.documents.size}"] = bytes
        vm.load("content://seed/${h.documents.documents.size - 1}")
        vm.onAction(ImportAction.Add(apply = false))
    }

    @Test
    fun tTheme34_identicalSaysAlreadyInYourThemes() {
        store(ThemeHarness.theme(idA, "Mine"))
        val vm = open(ThemeHarness.theme(idA, "Mine"))
        compose.onNodeWithText("Already in your themes").assertExists()
        compose.onAllNodesWithTag(ThemeTestTags.IMPORT_ADD).assertCountEquals(0)
        click(ThemeTestTags.IMPORT_ADD_APPLY) // "Apply"
        assertThat(storedIds()).containsExactly(idA)
        assertThat((vm.state.value as ImportUiState.Done).commit.applied).isTrue()
    }

    @Test
    fun tTheme34_sameIdAsksReplaceKeepBothCancel() {
        store(ThemeHarness.theme(idA, "Mine", ""","colors":{"light":{"accent":"#2F5D8A"}}"""))
        val changed = ThemeHarness.theme(idA, "Mine", ""","colors":{"light":{"accent":"#8A2F2F"}}""")
        open(changed)
        click(ThemeTestTags.IMPORT_ADD)
        compose.onNodeWithTag(ThemeTestTags.IMPORT_CONFLICT).assertExists()
        click(ThemeTestTags.IMPORT_KEEP_BOTH)
        val ids = storedIds()
        assertThat(ids).hasSize(2)
        assertThat(runBlocking { h.repo.entries() }.map { it.name }).contains("Mine (2)")
    }

    @Test
    fun tTheme34_replaceKeepsTheIdAndCanBeUndone() {
        store(ThemeHarness.theme(idA, "Mine", ""","colors":{"light":{"accent":"#2F5D8A"}}"""))
        val vm = open(ThemeHarness.theme(idA, "Mine", ""","colors":{"light":{"accent":"#8A2F2F"}}"""))
        click(ThemeTestTags.IMPORT_ADD)
        click(ThemeTestTags.IMPORT_REPLACE)
        assertThat(storedIds()).containsExactly(idA)
        assertThat((vm.state.value as ImportUiState.Done).commit.replaced).isTrue()
        compose.onNodeWithText("“Mine” replaced").assertExists()
        val replacedBytes = String(h.files.files.getValue("user/$idA.strata-theme.json"))
        assertThat(replacedBytes).contains("#8A2F2F")
        vm.onAction(ImportAction.UndoReplace)
        assertThat(String(h.files.files.getValue("user/$idA.strata-theme.json"))).contains("#2F5D8A")
    }

    @Test
    fun tTheme34_builtInIdIsAddedAsACopy() {
        val vm = open(ThemeHarness.theme(ThemeHarness.PAPER, "Paper", ""","colors":{"light":{"accent":"#112233"}}"""))
        assertThat((vm.state.value as ImportUiState.Summary).preview.conflict).isEqualTo(ImportConflict.BUILT_IN_ID)
        compose.onNodeWithText("Has the same id as a built-in theme, so it is added as a copy").assertExists()
        compose.onNodeWithText("Will be added as “Paper (2)”").assertExists()
        click(ThemeTestTags.IMPORT_ADD)
        assertThat(storedIds().single()).isNotEqualTo(ThemeHarness.PAPER)
    }

    @Test
    fun tTheme34_sameNameIsNumbered() {
        store(ThemeHarness.theme(idA, "Night"))
        open(ThemeHarness.theme("aaaaaaaa-0000-4000-8000-000000000002", "Night"))
        compose.onNodeWithText("Will be added as “Night (2)”").assertExists()
    }

    // ---- T-THEME-42 (import path) ---------------------------------------------------------------------

    @Test
    fun addAndApplyWithWallpaperAsksFirst() {
        open(BundleFixtures.bundle(font = null))
        click(ThemeTestTags.IMPORT_ADD_APPLY)
        compose.onNodeWithTag(ThemeTestTags.WALLPAPER_DIALOG).assertExists()
        assertThat(h.intents.wallpaperHandoffs).isEmpty()
        compose.onNodeWithTag(ThemeTestTags.WALLPAPER_YES).performClick()
        compose.waitForIdle()
        assertThat(h.intents.wallpaperHandoffs).hasSize(1)
    }

    // ---- review fixes: errors and pending imports ------------------------------------------------------

    @Test
    fun nonIoFailureWhileSavingIsAnErrorNotACrash() {
        val vm = open(ThemeHarness.theme(idA, "Boom"))
        h.files.unreadable.clear()
        h.files.writeFailure = IllegalStateException("simulated library bug")
        click(ThemeTestTags.IMPORT_ADD)
        assertThat(vm.state.value).isInstanceOf(ImportUiState.Failed::class.java)
        assertThat(storedIds()).isEmpty()
    }

    @Test
    fun undoOfASecondReplaceRestoresTheVersionJustReplaced() {
        store(ThemeHarness.theme(idA, "v1"))

        fun replace(name: String): ThemeImportViewModel {
            val vm = viewModel()
            h.documents.documents["content://r/$name"] = ThemeHarness.theme(idA, name)
            vm.load("content://r/$name")
            vm.onAction(ImportAction.Add(apply = false))
            vm.onAction(ImportAction.Resolve(app.strata.launcher.core.theme.importing.ConflictChoice.REPLACE))
            return vm
        }
        replace("v2")
        replace("v3").onAction(ImportAction.UndoReplace)
        assertThat(String(h.files.files.getValue("user/$idA.strata-theme.json"))).contains("\"v2\"")
    }

    @Test
    fun pendingImportsAreCapped() {
        val tokens =
            (1..6).map { i ->
                val p =
                    runBlocking {
                        h.repo.prepareImport(ThemeHarness.theme("aaaaaaaa-0000-4000-8000-00000000000$i", "T$i"))
                    } as app.strata.launcher.core.common.Outcome.Ok
                p.value.token
            }

        fun commit(t: String) =
            runBlocking {
                h.repo.commitImport(t, app.strata.launcher.core.theme.importing.ConflictChoice.KEEP_BOTH, apply = false)
            }
        // The two oldest were dropped; the last four still commit.
        assertThat(commit(tokens[0])).isInstanceOf(app.strata.launcher.core.common.Outcome.Err::class.java)
        assertThat(commit(tokens[5])).isInstanceOf(app.strata.launcher.core.common.Outcome.Ok::class.java)
    }
}
