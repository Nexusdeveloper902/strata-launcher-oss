package app.strata.launcher.feature.theme

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.platformapi.system.DocumentIO
import app.strata.launcher.core.platformapi.system.DocumentRead
import app.strata.launcher.core.theme.editor.ContrastReport
import app.strata.launcher.core.theme.editor.EditorContrast
import app.strata.launcher.core.theme.editor.EditorDraft
import app.strata.launcher.core.theme.editor.EditorFields
import app.strata.launcher.core.theme.editor.EditorSession
import app.strata.launcher.core.theme.editor.ImageTheme
import app.strata.launcher.core.theme.editor.JsonApply
import app.strata.launcher.core.theme.importing.ImageCheck
import app.strata.launcher.core.theme.model.ThemeDocument
import app.strata.launcher.core.theme.parse.Issue
import app.strata.launcher.core.theme.parse.Severity
import app.strata.launcher.core.theme.parse.ThemeError
import app.strata.launcher.core.theme.resolve.AppearanceOverride
import app.strata.launcher.core.theme.resolve.ThemeResolver
import app.strata.launcher.core.theme.store.EditTarget
import app.strata.launcher.core.theme.store.EditorSaveError
import app.strata.launcher.core.theme.store.ImageSeedError
import app.strata.launcher.core.theme.store.ThemeEditorStore
import app.strata.launcher.core.theme.store.ThemePair
import app.strata.launcher.core.theme.store.ThemeRepository
import app.strata.launcher.core.ui.ThemeApplyTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How the editor was opened (THEME_SYSTEM §13). */
sealed interface EditorRequest {
    /** Edit a theme: a stored theme as itself, a built-in as an unsaved copy. */
    data class Edit(val id: String) : EditorRequest

    /** A new theme from Strata Default's values. */
    data object New : EditorRequest

    /** A new theme whose palette comes from an image picked with the system photo picker. */
    data object FromImage : EditorRequest

    /** Continue an unsaved draft listed in the library. */
    data class Draft(val sessionId: String) : EditorRequest
}

enum class EditorMessage {
    SAVED,
    APPLIED,
    SAVE_FAILED,
    NOT_ENOUGH_STORAGE,
    JSON_APPLIED,

    /** The JSON named another id; the theme kept its own. */
    JSON_ID_KEPT,
    IMAGE_UNSUPPORTED,
    IMAGE_UNREADABLE,
    IMAGE_TOO_LARGE,
    IMAGE_APPLIED,
    NOTHING_TO_FIX,
    FIXED,
}

/** The JSON view (§13 "Edit as JSON"): the text being edited and what the validator says about it. */
data class JsonViewState(
    val text: String,
    val issues: List<Issue> = emptyList(),
    val error: ThemeError? = null,
)

data class DraftOffer(val savedAtMillis: Long)

data class EditorUiState(
    val loading: Boolean = true,
    /** The theme could not be opened (deleted meanwhile, needs an app update): the screen closes. */
    val missing: Boolean = false,
    val sessionId: String? = null,
    val document: ThemeDocument? = null,
    /** Every field's effective value (EditorFields.effective) for the controls. */
    val effective: JsonObject? = null,
    /** Preview of the edited theme, light and dark forced (§13 preview toggle). */
    val preview: ThemePair? = null,
    val previewDark: Boolean = false,
    val previewWallpaper: Boolean = true,
    val showProblems: Boolean = false,
    val sampleApps: Boolean = false,
    val appLabels: List<String> = emptyList(),
    val contrast: ContrastReport? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val dirty: Boolean = false,
    val isNew: Boolean = false,
    /** "Reset to *basedOn* preset" is offered when the preset still loads. */
    val basedOnName: String? = null,
    val draftOffer: DraftOffer? = null,
    val leavePrompt: Boolean = false,
    val json: JsonViewState? = null,
    val expandedGroup: String? = "colors",
    /** Swatches from the current wallpaper colours and the dynamic scheme (§13 colour control). */
    val swatches: List<Int> = emptyList(),
    val busy: Boolean = false,
    val keepImageAsWallpaper: Boolean = false,
    /** The screen is finished (saved and left, discarded, or nothing to edit). */
    val done: Boolean = false,
)

sealed interface EditorAction {
    /** A control changed [fieldId] to [value]; [dragging] while a slider is held or text is typed (coalesced). */
    data class Set(val fieldId: String, val value: Any, val dragging: Boolean = false) : EditorAction

    /** The drag or text-edit session ended (one undo step closes). */
    data object EndDrag : EditorAction

    data class Reset(val fieldId: String) : EditorAction

    data class ResetGroup(val key: String) : EditorAction

    /** [toPreset]: "Reset to *basedOn* preset"; else "Reset to Strata Default". */
    data class ResetTheme(val toPreset: Boolean) : EditorAction

    data object Undo : EditorAction

    data object Redo : EditorAction

    data object Fix : EditorAction

    /** Banner "Show me": the problem overlay on, preview in view. */
    data object ShowMe : EditorAction

    data class PreviewDark(val dark: Boolean) : EditorAction

    data class PreviewWallpaper(val on: Boolean) : EditorAction

    data class ShowProblems(val on: Boolean) : EditorAction

    data class SampleApps(val on: Boolean) : EditorAction

    data class Expand(val group: String?) : EditorAction

    data object OpenJson : EditorAction

    data class JsonChanged(val text: String) : EditorAction

    data object ApplyJson : EditorAction

    data object CloseJson : EditorAction

    data class KeepImageAsWallpaper(val keep: Boolean) : EditorAction

    /** "Colours from image…": start the system photo picker. */
    data object PickImage : EditorAction

    /** The photo picker's result (null when cancelled): the create-from-image seam. */
    data class ImagePicked(val uri: String?) : EditorAction

    data object Save : EditorAction

    /** Save, then make the theme active. */
    data object Apply : EditorAction

    data object Back : EditorAction

    data object LeaveSave : EditorAction

    data object LeaveDiscard : EditorAction

    data object KeepEditing : EditorAction

    data object RestoreDraft : EditorAction

    data object DiscardDraft : EditorAction

    /** The screen stopped: write the draft now. */
    data object Pause : EditorAction
}

sealed interface EditorEffect {
    /** Start the system photo picker (images only); the result comes back as [EditorAction.ImagePicked]. */
    data object PickImage : EditorEffect

    data object ScrollToPreview : EditorEffect

    data class Message(val message: EditorMessage) : EditorEffect
}

/**
 * The theme editor (THEME_SYSTEM §13, FR-THEME-29/30): live preview, undo/redo (≥ 100 steps, drags coalesced), field,
 * group and theme resets, the JSON view, drafts every 2 s with recovery after process death, create-from-image, and
 * the contrast chips, banner and Fix (§7.6).
 *
 * Session edits are pure and run on the main thread (each is one validation of a ≤ 256 KiB tree); the preview and
 * contrast resolution run on [computeDispatcher], conflated, so a slider drag never queues work. Every disk access
 * goes through [store] (its own dispatcher). Nothing here uses the Compose clock.
 */
class ThemeEditorViewModel(
    private val store: ThemeEditorStore,
    private val themes: ThemeRepository,
    private val documents: DocumentIO,
    private val handle: SavedStateHandle,
    private val request: EditorRequest,
    appLabels: Flow<List<String>> = flowOf(emptyList()),
    private val clock: () -> Long = System::currentTimeMillis,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Runs the 2 s draft timer; null = [viewModelScope]. */
    timerScope: CoroutineScope? = null,
    private val draftIntervalMs: Long = DRAFT_INTERVAL_MS,
    private val newThemeName: String = "New theme",
    private val imageThemeName: String = "Theme from image",
) : ViewModel() {
    private var session: EditorSession? = null
    private val local = MutableStateFlow(EditorUiState())
    private val docs = MutableStateFlow<ThemeDocument?>(null)
    private val effectChannel = Channel<EditorEffect>(Channel.BUFFERED)

    /** The tree last written as a draft (or the opening tree): the timer writes only when this differs. */
    private var draftTree: JsonObject? = null
    private var draftExists = false

    val effects: Flow<EditorEffect> = effectChannel.receiveAsFlow()

    private val stateFlow = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = stateFlow.asStateFlow()

    private data class Derived(
        val doc: ThemeDocument,
        val effective: JsonObject,
        val preview: ThemePair,
        val contrast: ContrastReport,
        val swatches: List<Int>,
    )

    private val derived = MutableStateFlow<Derived?>(null)

    init {
        themes.start()
        viewModelScope.launch {
            docs
                .filterNotNull()
                .map(::derive)
                .flowOn(computeDispatcher)
                .collect { derived.value = it }
        }
        viewModelScope.launch {
            combine(local, derived, appLabels.onStart { emit(emptyList()) }) { l, d, apps ->
                if (d == null) {
                    l.copy(appLabels = apps)
                } else {
                    l.copy(
                        effective = d.effective,
                        preview = d.preview,
                        contrast = d.contrast,
                        swatches = d.swatches,
                        appLabels = apps,
                    )
                }
            }.collect { stateFlow.value = it }
        }
        viewModelScope.launch { open() }
        (timerScope ?: viewModelScope).launch {
            while (isActive) {
                delay(draftIntervalMs)
                flushDraft()
            }
        }
    }

    private fun derive(doc: ThemeDocument): Derived {
        val env = store.previewEnvironment()
        // The preview shows the theme's own colours: fixed palettes are not adjusted, so an edit is always visible
        // (derived palettes are enforced by the resolver anyway, §5.2). The banner reports what enforcement would fix.
        val g = env.globals.copy(enforceContrast = false)
        val light =
            ThemeResolver
                .resolveSafely(
                    doc,
                    env.inputs.copy(systemDark = false),
                    g.copy(appearanceOverride = AppearanceOverride.LIGHT),
                ).theme
        val dark =
            ThemeResolver
                .resolveSafely(
                    doc,
                    env.inputs.copy(systemDark = true),
                    g.copy(appearanceOverride = AppearanceOverride.DARK),
                ).theme
        val swatches =
            buildList {
                env.inputs.wallpaperColors
                    ?.all
                    ?.let(::addAll)
                env.inputs.dynamicScheme?.let { s ->
                    add(s.light.primary)
                    add(s.dark.primary)
                    add(s.light.surface)
                    add(s.dark.surface)
                }
            }.distinct()
        return Derived(
            doc,
            EditorFields.effective(doc),
            ThemePair(light, dark),
            EditorContrast.report(doc, env.inputs, env.globals),
            swatches,
        )
    }

    // ---- opening ------------------------------------------------------------------------------------

    private suspend fun open() {
        try {
            val saved = handle.get<String>(KEY_SESSION)
            val target: EditTarget? =
                when {
                    saved != null && handle.get<Boolean>(KEY_NEW) == false -> {
                        store.openForEdit(saved)
                    }

                    saved != null -> {
                        store.readDraft(saved)?.let { d ->
                            EditTarget(d.sessionId, d.sourceId, d.tree, null).also { restoredDraft = d }
                        }
                            ?: start()
                    }

                    else -> {
                        start()
                    }
                }
            if (target == null) {
                local.update { it.copy(loading = false, missing = true, done = true) }
                return
            }
            begin(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Store calls never throw for bad data; anything else must not crash the editor (it just closes).
            local.update { it.copy(loading = false, missing = true, done = true) }
        }
    }

    /** A draft restored without asking (the library's "Continue editing", or a new session after process death). */
    private var restoredDraft: EditorDraft? = null

    private suspend fun start(): EditTarget? =
        when (request) {
            is EditorRequest.Edit -> {
                store.openForEdit(request.id)
            }

            EditorRequest.New -> {
                store.newTheme(newThemeName)
            }

            EditorRequest.FromImage -> {
                store.newTheme(imageThemeName)
            }

            is EditorRequest.Draft -> {
                store.readDraft(request.sessionId)?.let { d ->
                    restoredDraft = d
                    if (d.isNew) EditTarget(d.sessionId, d.sourceId, d.tree, null) else store.openForEdit(d.sessionId)
                }
            }
        }

    private suspend fun begin(target: EditTarget) {
        val restored = restoredDraft
        restoredDraft = null
        // A never-saved session restored from its draft has unsaved work by definition: always "dirty".
        val baseline = if (restored != null && target.saved == null) JsonObject.EMPTY else null
        val s = EditorSession(target.sessionId, target.sourceId, target.tree, target.saved, baseline = baseline)
        session = s
        handle[KEY_SESSION] = target.sessionId
        handle[KEY_NEW] = target.saved == null
        val draft = restored ?: store.readDraft(target.sessionId)
        draftExists = draft != null
        if (restored != null) {
            if (restored.tree != s.tree) s.replace(restored.tree)
        } else if (draft != null && draft.tree != s.tree) {
            pendingDraft = draft
            local.update { it.copy(draftOffer = DraftOffer(draft.savedAtMillis)) }
        }
        draftTree = s.tree
        val basedOn = s.document.basedOn
        val preset = basedOn?.let { store.themeTree(it) }
        basedOnTree = preset
        local.update {
            it.copy(
                loading = false,
                sessionId = target.sessionId,
                basedOnName = preset?.let { t -> (t["name"] as? JsonString)?.value },
            )
        }
        publish()
        if (request == EditorRequest.FromImage && handle.get<Boolean>(KEY_PICKED) != true && target.saved == null) {
            handle[KEY_PICKED] = true
            effectChannel.trySend(EditorEffect.PickImage)
        }
    }

    private var pendingDraft: EditorDraft? = null
    private var basedOnTree: JsonObject? = null

    // ---- actions ------------------------------------------------------------------------------------

    fun onAction(action: EditorAction) {
        val s = session
        when (action) {
            is EditorAction.PreviewDark -> return local.update { it.copy(previewDark = action.dark) }
            is EditorAction.PreviewWallpaper -> return local.update { it.copy(previewWallpaper = action.on) }
            is EditorAction.ShowProblems -> return local.update { it.copy(showProblems = action.on) }
            is EditorAction.SampleApps -> return local.update { it.copy(sampleApps = action.on) }
            is EditorAction.Expand -> return local.update { it.copy(expandedGroup = action.group) }
            is EditorAction.KeepImageAsWallpaper -> return local.update { it.copy(keepImageAsWallpaper = action.keep) }
            EditorAction.KeepEditing -> return local.update { it.copy(leavePrompt = false) }
            EditorAction.Pause -> return launchSafely { flushDraft() }
            else -> Unit
        }
        if (s == null) return
        when (action) {
            is EditorAction.Set -> {
                val field = EditorFields.field(action.fieldId) ?: return
                if (s.set(field, action.value, coalesce = action.dragging)) publish()
            }

            EditorAction.EndDrag -> {
                s.endCoalescing()
            }

            is EditorAction.Reset -> {
                EditorFields.field(action.fieldId)?.let { if (s.reset(it)) publish() }
            }

            is EditorAction.ResetGroup -> {
                EditorFields.group(action.key)?.let { if (s.resetGroup(it)) publish() }
            }

            is EditorAction.ResetTheme -> {
                if (s.resetTheme(if (action.toPreset) basedOnTree else null)) publish()
            }

            EditorAction.Undo -> {
                if (s.undo()) publish()
            }

            EditorAction.Redo -> {
                if (s.redo()) publish()
            }

            EditorAction.Fix -> {
                fix(s)
            }

            EditorAction.ShowMe -> {
                local.update { it.copy(showProblems = true) }
                effectChannel.trySend(EditorEffect.ScrollToPreview)
            }

            EditorAction.OpenJson -> {
                local.update { it.copy(json = JsonViewState(s.jsonText(), s.parsed.issues.visible())) }
            }

            is EditorAction.JsonChanged -> {
                jsonChanged(s, action.text)
            }

            EditorAction.ApplyJson -> {
                applyJson(s)
            }

            EditorAction.CloseJson -> {
                local.update { it.copy(json = null) }
            }

            EditorAction.PickImage -> {
                effectChannel.trySend(EditorEffect.PickImage)
            }

            is EditorAction.ImagePicked -> {
                imagePicked(s, action.uri)
            }

            EditorAction.Save -> {
                launchSafely { save(s, apply = false, leave = false) }
            }

            EditorAction.Apply -> {
                launchSafely { save(s, apply = true, leave = false) }
            }

            EditorAction.Back -> {
                if (s.dirty) local.update { it.copy(leavePrompt = true) } else leave(discard = true)
            }

            EditorAction.LeaveSave -> {
                local.update { it.copy(leavePrompt = false) }
                launchSafely { save(s, apply = false, leave = true) }
            }

            EditorAction.LeaveDiscard -> {
                local.update { it.copy(leavePrompt = false) }
                leave(discard = true)
            }

            EditorAction.RestoreDraft -> {
                val d = pendingDraft ?: return
                pendingDraft = null
                s.replace(d.tree)
                draftTree = s.tree
                local.update { it.copy(draftOffer = null) }
                publish()
            }

            EditorAction.DiscardDraft -> {
                pendingDraft = null
                local.update { it.copy(draftOffer = null) }
                launchSafely { deleteDraft(s.sessionId) }
            }

            // Handled above, before the session check.
            is EditorAction.PreviewDark, is EditorAction.PreviewWallpaper, is EditorAction.ShowProblems,
            is EditorAction.SampleApps, is EditorAction.Expand, is EditorAction.KeepImageAsWallpaper,
            EditorAction.KeepEditing, EditorAction.Pause,
            -> {}
        }
    }

    private fun publish() {
        val s = session ?: return
        docs.value = s.document
        local.update {
            it.copy(
                document = s.document,
                canUndo = s.canUndo,
                canRedo = s.canRedo,
                dirty = s.dirty,
                isNew = s.isNew,
            )
        }
    }

    private fun fix(s: EditorSession) {
        val env = store.previewEnvironment()
        val tree = s.tree
        launchSafely {
            val next =
                withContext(computeDispatcher) {
                    EditorContrast.fix(tree, s::documentOf, env.inputs, env.globals)
                }
            if (s.replace(next)) {
                publish()
                effectChannel.trySend(EditorEffect.Message(EditorMessage.FIXED))
            } else {
                effectChannel.trySend(EditorEffect.Message(EditorMessage.NOTHING_TO_FIX))
            }
        }
    }

    private var jsonCheck: Job? = null

    private fun jsonChanged(
        s: EditorSession,
        text: String,
    ) {
        local.update { it.copy(json = it.json?.copy(text = text) ?: JsonViewState(text)) }
        jsonCheck?.cancel()
        jsonCheck =
            viewModelScope.launch {
                val r = withContext(computeDispatcher) { s.checkJson(text) }
                local.update { st ->
                    val j = st.json ?: return@update st
                    if (j.text != text) return@update st
                    when (r) {
                        is Outcome.Ok -> st.copy(json = j.copy(issues = r.value.issues.visible(), error = null))
                        is Outcome.Err -> st.copy(json = j.copy(issues = emptyList(), error = r.error))
                    }
                }
            }
    }

    private fun applyJson(s: EditorSession) {
        val text = local.value.json?.text ?: return
        when (val r = s.applyJson(text)) {
            is JsonApply.Applied -> {
                local.update { it.copy(json = null) }
                publish()
                val m = if (r.idKept) EditorMessage.JSON_ID_KEPT else EditorMessage.JSON_APPLIED
                effectChannel.trySend(EditorEffect.Message(m))
            }

            is JsonApply.Rejected -> {
                local.update { it.copy(json = it.json?.copy(error = r.error, issues = emptyList())) }
            }
        }
    }

    private fun imagePicked(
        s: EditorSession,
        uri: String?,
    ) {
        if (uri == null) {
            // Cancelled on a fresh create-from-image session: nothing to edit.
            if (request == EditorRequest.FromImage && s.isNew && !s.canUndo) leave(discard = true)
            return
        }
        val keep = local.value.keepImageAsWallpaper
        local.update { it.copy(busy = true) }
        launchSafely {
            try {
                val bytes =
                    when (val r = documents.read(uri, ImageCheck.MAX_BYTES)) {
                        is DocumentRead.Ok -> r.bytes

                        DocumentRead.TooLarge -> return@launchSafely message(EditorMessage.IMAGE_TOO_LARGE)

                        DocumentRead.UnsupportedScheme, DocumentRead.Failed -> return@launchSafely message(
                            EditorMessage.IMAGE_UNREADABLE,
                        )
                    }
                when (val r = store.imageSeed(bytes, keep)) {
                    is Outcome.Ok -> {
                        val wallpaper = s.document.wallpaper
                        val tree = s.tree
                        val next =
                            withContext(computeDispatcher) {
                                ImageTheme.apply(tree, r.value.seed, wallpaper, r.value.image)
                            }
                        if (s.replace(next)) publish()
                        message(EditorMessage.IMAGE_APPLIED)
                    }

                    is Outcome.Err -> {
                        message(
                            when (r.error) {
                                ImageSeedError.UNSUPPORTED -> {
                                    EditorMessage.IMAGE_UNSUPPORTED
                                }

                                ImageSeedError.UNREADABLE -> {
                                    EditorMessage.IMAGE_UNREADABLE
                                }
                            },
                        )
                    }
                }
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    private fun message(m: EditorMessage) {
        effectChannel.trySend(EditorEffect.Message(m))
    }

    private suspend fun save(
        s: EditorSession,
        apply: Boolean,
        leave: Boolean,
    ) {
        val tree = s.tree
        when (val r = store.saveEdited(tree)) {
            is Outcome.Ok -> {
                s.markSaved(r.value)
                handle[KEY_NEW] = false
                deleteDraft(s.sessionId)
                draftTree = s.tree
                publish()
                if (apply) {
                    ThemeApplyTrace.begin(themes.state.value.applyGeneration)
                    themes.apply(s.sessionId)
                }
                message(if (apply) EditorMessage.APPLIED else EditorMessage.SAVED)
                if (leave) local.update { it.copy(done = true) }
            }

            is Outcome.Err -> {
                message(
                    when (r.error) {
                        EditorSaveError.NOT_ENOUGH_STORAGE -> {
                            EditorMessage.NOT_ENOUGH_STORAGE
                        }

                        EditorSaveError.INVALID -> {
                            EditorMessage.SAVE_FAILED
                        }
                    },
                )
            }
        }
    }

    private fun leave(discard: Boolean) {
        val s = session
        local.update { it.copy(done = true) }
        if (discard && s != null) {
            // The app scope is not needed: the store finishes the delete on its own dispatcher.
            launchSafely { deleteDraft(s.sessionId) }
        }
    }

    private suspend fun deleteDraft(sessionId: String) {
        if (!draftExists) return
        store.deleteDraft(sessionId)
        draftExists = false
    }

    /** The 2 s autosave (§13): writes the draft when the tree changed since the last write; drops it when clean. */
    private suspend fun flushDraft() {
        val s = session ?: return
        if (local.value.done || pendingDraft != null) return
        val tree = s.tree
        if (!s.dirty) {
            if (draftExists) deleteDraft(s.sessionId)
            draftTree = tree
            return
        }
        if (tree === draftTree) return
        val ok = store.writeDraft(EditorDraft(s.sessionId, s.sourceId, s.isNew, clock(), tree))
        if (ok) {
            draftTree = tree
            draftExists = true
        }
    }

    /** User operations never crash the screen (NFR-THEME-01): unexpected failures become a message. */
    private fun launchSafely(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message(EditorMessage.SAVE_FAILED)
            }
        }
    }

    override fun onCleared() {
        jsonCheck?.cancel()
        super.onCleared()
    }

    /** Only issues worth showing in the JSON view (not `info`). */
    private fun List<Issue>.visible(): List<Issue> = filter { it.severity != Severity.INFO }

    companion object {
        /** §13: the draft is autosaved every 2 s. */
        const val DRAFT_INTERVAL_MS = 2_000L
        private const val KEY_SESSION = "editor.session"
        private const val KEY_NEW = "editor.new"
        private const val KEY_PICKED = "editor.picked"
    }
}
