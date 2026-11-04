package app.strata.launcher.feature.theme

import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonParser
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.contrast.Contrast
import app.strata.launcher.core.theme.contrast.ContrastPair
import app.strata.launcher.core.theme.importing.ConflictChoice
import app.strata.launcher.core.theme.model.ColorRole
import app.strata.launcher.core.theme.model.Variant
import app.strata.launcher.core.theme.store.EditTarget
import app.strata.launcher.core.theme.store.ImageSeedError
import app.strata.launcher.core.theme.store.ImageSeedResult
import app.strata.launcher.core.theme.store.ThemeEditorStore
import app.strata.launcher.core.ui.StrataDefaultTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
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
 * The theme editor (Robolectric, the real repository over in-memory stores): T-THEME-24 (C: chips, banner in words,
 * Fix in one undo step, polite status), T-THEME-37 (C/R: ≥ 100 undo steps, a drag is one step, draft written every 2 s
 * and offered after a simulated process death), resets, the JSON view, built-in copies and the leave prompt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ThemeEditorTest {
    @get:Rule
    val compose = createComposeRule()

    private val h = ThemeHarness()
    private val timer = TestScope(StandardTestDispatcher())

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val idA = "aaaaaaaa-0000-4000-8000-0000000000e1"

    private fun store(
        bytes: ByteArray,
        harness: ThemeHarness = h,
    ): String =
        runBlocking {
            val p = (harness.repo.prepareImport(bytes) as Outcome.Ok).value
            (harness.repo.commitImport(p.token, ConflictChoice.KEEP_BOTH, apply = false) as Outcome.Ok).value.id
        }

    private fun vm(
        request: EditorRequest,
        harness: ThemeHarness = h,
        handle: SavedStateHandle = SavedStateHandle(),
    ) = ThemeEditorViewModel(
        store = harness.repo,
        themes = harness.repo,
        documents = harness.documents,
        handle = handle,
        request = request,
        computeDispatcher = Dispatchers.Unconfined,
        timerScope = timer,
    )

    private fun show(vm: ThemeEditorViewModel) {
        compose.setContent {
            StrataDefaultTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                ThemeEditorScreen(state, vm::onAction)
            }
        }
        compose.waitForIdle()
    }

    private val failingP2P5 =
        ThemeHarness.theme(idA, "Faded", ""","colors":{"mode":"light","light":{"textSecondary":"#BBBBBB"}}""")

    // ---- T-THEME-24 ---------------------------------------------------------------------------------

    @Test
    fun tTheme24_chipsBannerFixAndUndo() {
        val id = store(failingP2P5)
        val vm = vm(EditorRequest.Edit(id))
        show(vm)
        compose.onNodeWithTag(EditorTestTags.BANNER_STATUS).assertTextContains("Some colours are hard to read")
        // Polite live region (announced once per change of the problem set, not per tick).
        compose
            .onNodeWithTag(EditorTestTags.BANNER_STATUS)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose
            .onNodeWithTag(EditorTestTags.pairLine(ContrastPair.P2, Variant.LIGHT))
            .assertTextContains("Secondary text on the background is hard to read", substring = true)
        compose
            .onNodeWithTag(EditorTestTags.pairLine(ContrastPair.P5, Variant.LIGHT))
            .assertTextContains("Secondary text on the search bar is hard to read", substring = true)
        assertThat(
            vm.state.value.contrast!!
                .failing
                .map { it.pair },
        ).containsExactly(ContrastPair.P2, ContrastPair.P5)

        // Chips: ratio + text/icon status.
        compose
            .onNodeWithTag(EditorTestTags.chip(Variant.LIGHT, ColorRole.TEXT_SECONDARY.wire))
            .performScrollTo()
            .assertTextContains("hard to read", substring = true)
        compose
            .onNodeWithTag(EditorTestTags.chip(Variant.LIGHT, ColorRole.TEXT_PRIMARY.wire))
            .performScrollTo()
            .assertTextContains("AA", substring = true)

        // A drag that keeps the same failing pairs never changes the announced text.
        val status = { compose.onNodeWithTag(EditorTestTags.BANNER_STATUS).fetchSemanticsNode().config }
        val before = status().getOrNull(SemanticsProperties.Text).toString()
        for (g in listOf("#BCBCBC", "#BDBDBD", "#C0C0C0")) {
            vm.onAction(EditorAction.Set("colors.light.textSecondary", g, dragging = true))
        }
        vm.onAction(EditorAction.EndDrag)
        compose.waitForIdle()
        assertThat(status().getOrNull(SemanticsProperties.Text).toString()).isEqualTo(before)
        vm.onAction(EditorAction.Undo)
        assertThat(vm.state.value.canUndo).isFalse()

        compose.onNodeWithTag(EditorTestTags.FIX).performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(
            vm.state.value.contrast!!
                .allPass,
        ).isTrue()
        compose
            .onNodeWithTag(
                EditorTestTags.BANNER_STATUS,
            ).assertTextContains("readable on any wallpaper", substring = true)
        assertThat(Contrast.staticCheck(vm.state.value.document!!).all { it.passes }).isTrue()

        // One undo step restores the failing palette.
        vm.onAction(EditorAction.Undo)
        compose.waitForIdle()
        assertThat(vm.state.value.canUndo).isFalse()
        assertThat(
            vm.state.value.contrast!!
                .failing
                .map { it.pair },
        ).containsExactly(ContrastPair.P2, ContrastPair.P5)
    }

    // ---- T-THEME-37 ---------------------------------------------------------------------------------

    @Test
    fun tTheme37_120EditsAndACoalescedDrag() {
        val id = store(ThemeHarness.theme(idA, "Mine"))
        val vm = vm(EditorRequest.Edit(id))
        repeat(120) { i -> vm.onAction(EditorAction.Set("icons.sizeDp", if (i % 2 == 0) 40 else 41)) }
        // A drag of the scale slider: many ticks, one step.
        for (v in 0..200) vm.onAction(EditorAction.Set("typography.scale", 0.8 + v * 0.0035, dragging = true))
        vm.onAction(EditorAction.EndDrag)
        val doc = { vm.state.value.document!! }
        assertThat(doc().typography.scale).isEqualTo(1.5)
        // One undo removes the whole drag (and nothing else): the drag was a single step.
        vm.onAction(EditorAction.Undo)
        assertThat(doc().typography.scale).isEqualTo(1.0)
        assertThat(doc().icons.sizeDp).isEqualTo(41)
        vm.onAction(EditorAction.Redo)
        assertThat(doc().typography.scale).isEqualTo(1.5)
        var undone = 0
        while (vm.state.value.canUndo) {
            vm.onAction(EditorAction.Undo)
            undone++
        }
        // 120 field edits + 1 drag step.
        assertThat(undone).isEqualTo(121)
    }

    // ---- review fixes: the draft offer, picker results, names --------------------------------------

    @Test
    fun draftOfferIsModalAndNeverLostUnanswered() {
        val id = store(ThemeHarness.theme(idA, "Mine"))
        val handle = SavedStateHandle()
        val first = vm(EditorRequest.Edit(id), handle = handle)
        first.onAction(EditorAction.Set("icons.sizeDp", 48))
        timer.advanceTimeBy(2_100)
        timer.runCurrent()
        assertThat(runBlocking { h.repo.readDraft(id) }).isNotNull()

        val next = ThemeHarness(files = h.files, index = h.index)
        val second = vm(EditorRequest.Edit(id), harness = next, handle = handle)
        assertThat(second.state.value.draftOffer).isNotNull()
        // While unanswered, nothing edits the session or saves.
        second.onAction(EditorAction.Set("icons.sizeDp", 60))
        second.onAction(EditorAction.Save)
        assertThat(
            second.state.value.document!!
                .icons.sizeDp,
        ).isEqualTo(32)
        // Back on the (clean) session leaves without deleting the recoverable draft.
        second.onAction(EditorAction.Back)
        assertThat(second.state.value.done).isTrue()
        timer.advanceTimeBy(4_100)
        timer.runCurrent()
        assertThat(runBlocking { next.repo.readDraft(id) }?.tree?.toString()).contains("48")

        // Discard answers it: edits work again and autosave resumes.
        val third = vm(EditorRequest.Edit(id), harness = next, handle = handle)
        third.onAction(EditorAction.DiscardDraft)
        assertThat(runBlocking { next.repo.readDraft(id) }).isNull()
        third.onAction(EditorAction.Set("icons.sizeDp", 52))
        assertThat(
            third.state.value.document!!
                .icons.sizeDp,
        ).isEqualTo(52)
        timer.advanceTimeBy(2_100)
        timer.runCurrent()
        assertThat(runBlocking { next.repo.readDraft(id) }?.tree?.toString()).contains("52")
    }

    @Test
    fun pickerResultBeforeTheSessionIsKept() {
        val gate = CompletableDeferred<Unit>()
        val seed = 0xFF2070D0.toInt()
        val store =
            object : ThemeEditorStore by h.repo {
                override suspend fun newTheme(name: String): EditTarget {
                    gate.await()
                    return h.repo.newTheme(name)
                }

                override suspend fun imageSeed(
                    bytes: ByteArray,
                    keepAsWallpaper: Boolean,
                ): Outcome<ImageSeedResult, ImageSeedError> = Outcome.Ok(ImageSeedResult(seed, null))
            }
        val uri = "content://test/picked.jpg"
        h.documents.documents[uri] = byteArrayOf(1, 2, 3)
        val handle = SavedStateHandle()
        val vm =
            ThemeEditorViewModel(
                store = store,
                themes = h.repo,
                documents = h.documents,
                handle = handle,
                request = EditorRequest.FromImage,
                computeDispatcher = Dispatchers.Unconfined,
                timerScope = timer,
            )
        // Process death while the picker was open: its result arrives before the session exists.
        vm.onAction(EditorAction.ImagePicked(uri))
        assertThat(vm.state.value.document).isNull()
        gate.complete(Unit)
        val doc = vm.state.value.document!!
        assertThat(doc.colors.sourceKind.wire).isEqualTo("seed")
        assertThat(doc.colors.seedColor).isEqualTo(seed)
    }

    @Test
    fun theNameCannotBeEmptied() {
        val id = store(ThemeHarness.theme(idA, "Mine"))
        val vm = vm(EditorRequest.Edit(id))
        vm.onAction(EditorAction.Set("name", "", dragging = true))
        vm.onAction(EditorAction.Set("name", "   ", dragging = true))
        assertThat(
            vm.state.value.document!!
                .name,
        ).isEqualTo("Mine")
        assertThat(vm.state.value.dirty).isFalse()
    }

    @Test
    fun tTheme37_draftEvery2sOfferedAfterProcessDeath() {
        val id = store(ThemeHarness.theme(idA, "Mine"))
        val handle = SavedStateHandle()
        val first = vm(EditorRequest.Edit(id), handle = handle)
        first.onAction(EditorAction.Set("icons.sizeDp", 48))
        first.onAction(EditorAction.Set("typography.scale", 1.2, dragging = true)) // mid-drag
        timer.advanceTimeBy(1_000)
        timer.runCurrent()
        assertThat(runBlocking { h.repo.readDraft(id) }).isNull()
        timer.advanceTimeBy(1_100)
        timer.runCurrent()
        val draft = runBlocking { h.repo.readDraft(id) }!!
        assertThat(draft.isNew).isFalse()

        // "Kill": a new repository (process) over the same files, the activity's saved state restored.
        val next = ThemeHarness(files = h.files, index = h.index)
        val second = vm(EditorRequest.Edit(id), harness = next, handle = handle)
        show(second)
        compose.onNodeWithTag(EditorTestTags.DRAFT_OFFER).assertIsDisplayed()
        assertThat(
            second.state.value.document!!
                .icons.sizeDp,
        ).isEqualTo(32)
        compose.onNodeWithTag(EditorTestTags.DRAFT_RESTORE).performClick()
        compose.waitForIdle()
        assertThat(
            second.state.value.document!!
                .icons.sizeDp,
        ).isEqualTo(48)
        assertThat(
            second.state.value.document!!
                .typography.scale,
        ).isEqualTo(1.2)
        assertThat(second.state.value.dirty).isTrue()

        // Save writes the theme and removes the draft.
        second.onAction(EditorAction.Save)
        assertThat(runBlocking { next.repo.readDraft(id) }).isNull()
        assertThat(runBlocking { next.repo.themeTree(id) }!!["icons"].toString()).contains("48")
    }

    @Test
    fun newSessionDraftIsRestoredWithoutAsking() {
        val handle = SavedStateHandle()
        val first = vm(EditorRequest.New, handle = handle)
        first.onAction(EditorAction.Set("list.density", "spacious"))
        timer.advanceTimeBy(2_100)
        timer.runCurrent()
        val second = vm(EditorRequest.New, harness = ThemeHarness(files = h.files, index = h.index), handle = handle)
        assertThat(second.state.value.draftOffer).isNull()
        assertThat(
            second.state.value.document!!
                .list.density.wire,
        ).isEqualTo("spacious")
        assertThat(second.state.value.dirty).isTrue()
    }

    // ---- resets, JSON view, copies, leaving ------------------------------------------------------------

    @Test
    fun builtInOpensAsAnUnsavedCopySavedOnlyOnSave() {
        val before = runBlocking { h.repo.entries() }.size
        val vm = vm(EditorRequest.Edit(ThemeHarness.PAPER))
        val s = vm.state.value
        assertThat(s.isNew).isTrue()
        assertThat(s.document!!.name).endsWith("(copy)")
        assertThat(s.basedOnName).isNotNull()
        assertThat(runBlocking { h.repo.entries() }.size).isEqualTo(before)
        vm.onAction(EditorAction.Set("icons.sizeDp", 50))
        vm.onAction(EditorAction.ResetTheme(toPreset = true))
        assertThat(
            vm.state.value.document!!
                .icons.sizeDp,
        ).isNotEqualTo(50)
        vm.onAction(EditorAction.Save)
        assertThat(runBlocking { h.repo.entries() }.size).isEqualTo(before + 1)
        assertThat(vm.state.value.isNew).isFalse()
    }

    @Test
    fun resetsAndJsonView() {
        val id = store(ThemeHarness.theme(idA, "Mine", ""","icons":{"sizeDp":50},"x-own":{"a":1}"""))
        val vm = vm(EditorRequest.Edit(id))
        show(vm)
        vm.onAction(EditorAction.Expand("icons"))
        compose.waitForIdle()
        compose.onNodeWithTag(EditorTestTags.reset("icons.sizeDp")).performScrollTo().performClick()
        assertThat(
            vm.state.value.document!!
                .icons.sizeDp,
        ).isEqualTo(32)
        vm.onAction(EditorAction.Undo)
        compose.onNodeWithTag(EditorTestTags.resetGroup("icons")).performScrollTo().performClick()
        assertThat(
            vm.state.value.document!!
                .icons.sizeDp,
        ).isEqualTo(32)
        vm.onAction(EditorAction.ResetTheme(toPreset = false))
        assertThat(
            vm.state.value.document!!.copy(
                id = StrataDefault.ID,
                name = StrataDefault.NAME,
                createdAt = null,
                modifiedAt = null,
            ),
        ).isEqualTo(StrataDefault.document)

        compose.onNodeWithTag(EditorTestTags.JSON).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(EditorTestTags.JSON_VIEW).assertIsDisplayed()
        vm.onAction(EditorAction.JsonChanged("""{"schemaVersion":1,"id":"$id","name":"Mine", """))
        compose.waitForIdle()
        assertThat(
            vm.state.value.json!!
                .error,
        ).isNotNull()
        vm.onAction(
            EditorAction.JsonChanged("""{"schemaVersion":1,"id":"$id","name":"Json","list":{"density":"compact"}}"""),
        )
        assertThat(
            vm.state.value.json!!
                .error,
        ).isNull()
        compose.onNodeWithTag(EditorTestTags.JSON_APPLY).performClick()
        compose.waitForIdle()
        assertThat(vm.state.value.json).isNull()
        assertThat(
            vm.state.value.document!!
                .name,
        ).isEqualTo("Json")
        vm.onAction(EditorAction.Undo)
        assertThat(
            vm.state.value.document!!
                .name,
        ).isEqualTo("Mine")
    }

    @Test
    fun leavingWithChangesAsks() {
        val id = store(ThemeHarness.theme(idA, "Mine"))
        val vm = vm(EditorRequest.Edit(id))
        show(vm)
        vm.onAction(EditorAction.Back)
        assertThat(vm.state.value.done).isTrue()

        val vm2 = vm(EditorRequest.Edit(id))
        vm2.onAction(EditorAction.Set("icons.sizeDp", 44))
        vm2.onAction(EditorAction.Back)
        assertThat(vm2.state.value.leavePrompt).isTrue()
        vm2.onAction(EditorAction.KeepEditing)
        assertThat(vm2.state.value.leavePrompt).isFalse()
        vm2.onAction(EditorAction.Back)
        vm2.onAction(EditorAction.LeaveSave)
        assertThat(vm2.state.value.done).isTrue()
        val stored = runBlocking { h.repo.themeTree(id) }!!
        assertThat(stored["icons"].toString()).contains("44")
    }

    @Test
    fun savingTheActiveThemeRerendersIt() {
        val id = store(ThemeHarness.theme(idA, "Mine"))
        h.repo.apply(id)
        val vm = vm(EditorRequest.Edit(id))
        vm.onAction(EditorAction.Set("icons.sizeDp", 60))
        vm.onAction(EditorAction.Save)
        assertThat(runBlocking { h.repo.state.first { it.themes.light.icons.sizeDp == 60 } }).isNotNull()
    }

    @Test
    fun applySavesAndActivates() {
        val vm = vm(EditorRequest.New)
        vm.onAction(EditorAction.Set("list.density", "compact"))
        vm.onAction(EditorAction.Apply)
        val id = vm.state.value.sessionId!!
        assertThat(runBlocking { h.repo.state.first { it.activeId.equals(id, ignoreCase = true) } }).isNotNull()
    }

    @Test
    fun libraryOffersEditAndListsDrafts() {
        val handle = SavedStateHandle()
        val editor = vm(EditorRequest.New, handle = handle)
        editor.onAction(EditorAction.Set("list.density", "compact"))
        timer.advanceTimeBy(2_100)
        timer.runCurrent()
        val lib = ThemeLibraryViewModel(h.repo, h.repo, h.documents, h.shared, h.intents, timer, editor = h.repo)
        lib.refresh()
        val drafts = lib.state.value.drafts
        assertThat(drafts.map { it.sessionId }).containsExactly(editor.state.value.sessionId)
        lib.onAction(LibraryAction.DiscardDraft(drafts.single().sessionId))
        assertThat(runBlocking { h.repo.drafts() }).isEmpty()
    }

    private fun json(text: String): JsonObject =
        (
            JsonParser.parse(
                text.toByteArray(),
            ) as Outcome.Ok
        ).value as JsonObject
}
