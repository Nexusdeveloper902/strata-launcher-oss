package app.strata.launcher.feature.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.platformapi.apps.LaunchResult
import app.strata.launcher.core.platformapi.system.DocumentIO
import app.strata.launcher.core.platformapi.system.SharedFiles
import app.strata.launcher.core.platformapi.system.SystemIntents
import app.strata.launcher.core.platformapi.system.WallpaperHandoff
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.store.DeleteTicket
import app.strata.launcher.core.theme.store.ExportError
import app.strata.launcher.core.theme.store.ExportFile
import app.strata.launcher.core.theme.store.ThemeManager
import app.strata.launcher.core.theme.store.ThemeRepository
import app.strata.launcher.core.theme.store.ThemeSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Messages the library shows once (toast-like, polite). */
enum class LibraryMessage {
    EXPORTED,
    EXPORT_FAILED,

    /** The bundle would exceed the import limits (§10.3.3): export the theme only. */
    EXPORT_TOO_LARGE,

    /** Undo arrived after the 5 s window had closed and the delete ran. */
    UNDO_TOO_LATE,
    SHARE_FAILED,
    DUPLICATE_FAILED,
    NO_WALLPAPER_APP,
    WALLPAPER_FAILED,
    NOT_APPLICABLE,
}

data class LibraryUiState(
    val loading: Boolean = true,
    /** Rows to show: hidden built-ins only while [showHidden]; a pending delete is already gone. */
    val rows: List<ThemeSummary> = emptyList(),
    val activeId: String = StrataDefault.ID,
    val showHidden: Boolean = false,
    val hiddenCount: Int = 0,
    /** The delete waiting for its 5 s undo window (§11.3). */
    val pendingDelete: DeleteTicket? = null,
    /** Export format choice for a theme with fonts or a wallpaper ("Include fonts and wallpaper (.zip)", §10.2). */
    val exportChoiceFor: ThemeSummary? = null,
    /** "Also set this theme's wallpaper?" for the theme just applied (§4.3, FR-THEME-34). */
    val wallpaperPromptFor: ThemeSummary? = null,
)

sealed interface LibraryAction {
    data class Apply(val id: String) : LibraryAction

    data class Duplicate(val id: String) : LibraryAction

    data class Delete(val id: String) : LibraryAction

    data object UndoDelete : LibraryAction

    data class SetHidden(val id: String, val hidden: Boolean) : LibraryAction

    data class ShowHidden(val show: Boolean) : LibraryAction

    data class Export(val id: String) : LibraryAction

    /** [bundle] from the format choice: `.zip` with fonts and wallpaper, else JSON only. */
    data class ExportAs(val id: String, val bundle: Boolean) : LibraryAction

    data object DismissExportChoice : LibraryAction

    /** The `ACTION_CREATE_DOCUMENT` result; null when cancelled. */
    data class ExportTo(val uri: String?) : LibraryAction

    /** `ACTION_CREATE_DOCUMENT` could not be started (no document provider). */
    data object ExportUnavailable : LibraryAction

    data class Share(val id: String) : LibraryAction

    data class Wallpaper(val accept: Boolean) : LibraryAction

    data object Import : LibraryAction
}

sealed interface LibraryEffect {
    /** Start `ACTION_OPEN_DOCUMENT` with the in-app import types (THEME_SYSTEM §3.1). */
    data object PickImport : LibraryEffect

    /** Start `ACTION_CREATE_DOCUMENT` for [file]; the result comes back as [LibraryAction.ExportTo]. */
    data class CreateDocument(val suggestedName: String, val mimeType: String) : LibraryEffect

    data class Message(val message: LibraryMessage) : LibraryEffect
}

/**
 * Theme library (FR-THEME-27): built-ins and unlimited user themes, apply, duplicate, delete with a 5-second undo,
 * hide built-ins, export to a document or share (FR-THEME-23), and the wallpaper confirmation after applying a theme
 * that carries one (FR-THEME-34). Nothing is gated (FR-THEME-01).
 *
 * The delete commit runs on [appScope], so leaving the screen inside the undo window still deletes after 5 s.
 */
class ThemeLibraryViewModel(
    private val manager: ThemeManager,
    private val themes: ThemeRepository,
    private val documents: DocumentIO,
    private val sharedFiles: SharedFiles,
    private val systemIntents: SystemIntents,
    private val appScope: CoroutineScope,
    private val shareTitle: String = "Share theme",
    private val undoWindowMs: Long = UNDO_WINDOW_MS,
) : ViewModel() {
    private val all = MutableStateFlow<List<ThemeSummary>?>(null)
    private val local = MutableStateFlow(LibraryUiState())
    private val effectChannel = Channel<LibraryEffect>(Channel.BUFFERED)
    private var deleteJob: Job? = null
    private val deleteMutex = Mutex()
    private var pendingExport: ExportFile? = null
    private val wallpaper = WallpaperOffer(manager, sharedFiles, systemIntents)

    val effects: Flow<LibraryEffect> = effectChannel.receiveAsFlow()

    private val stateFlow = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = stateFlow.asStateFlow()

    init {
        themes.start()
        viewModelScope.launch { manager.revision.collect { reload() } }
        viewModelScope.launch {
            combine(all, local, themes.state) { list, l, t ->
                val pending = l.pendingDelete?.id
                val visible =
                    list
                        .orEmpty()
                        .filterNot { it.id.equals(pending, ignoreCase = true) }
                        .filter { l.showHidden || !it.hidden }
                l.copy(
                    loading = list == null,
                    rows = visible,
                    activeId = t.activeId,
                    hiddenCount = list.orEmpty().count { it.hidden },
                )
            }.collect { stateFlow.value = it }
        }
    }

    private suspend fun reload() {
        all.value = manager.entries()
    }

    fun onAction(action: LibraryAction) {
        when (action) {
            is LibraryAction.Apply -> apply(action.id)
            is LibraryAction.Duplicate -> duplicate(action.id)
            is LibraryAction.Delete -> delete(action.id)
            LibraryAction.UndoDelete -> undoDelete()
            is LibraryAction.SetHidden -> launch { manager.setHidden(action.id, action.hidden) }
            is LibraryAction.ShowHidden -> local.update { it.copy(showHidden = action.show) }
            is LibraryAction.Export -> export(action.id)
            is LibraryAction.ExportAs -> exportAs(action.id, action.bundle)
            LibraryAction.DismissExportChoice -> local.update { it.copy(exportChoiceFor = null) }
            is LibraryAction.ExportTo -> exportTo(action.uri)
            LibraryAction.ExportUnavailable -> exportUnavailable()
            is LibraryAction.Share -> share(action.id)
            is LibraryAction.Wallpaper -> wallpaperAnswer(action.accept)
            LibraryAction.Import -> effectChannel.trySend(LibraryEffect.PickImport)
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun emit(m: LibraryMessage) {
        effectChannel.trySend(LibraryEffect.Message(m))
    }

    private fun summary(id: String): ThemeSummary? = all.value?.firstOrNull { it.id.equals(id, ignoreCase = true) }

    private fun apply(id: String) {
        val t = summary(id) ?: return
        if (!t.applicable) {
            emit(LibraryMessage.NOT_APPLICABLE)
            return
        }
        themes.apply(id)
        launch { if (wallpaper.available(id)) local.update { it.copy(wallpaperPromptFor = t) } }
    }

    private fun wallpaperAnswer(accept: Boolean) {
        val t = local.value.wallpaperPromptFor ?: return
        local.update { it.copy(wallpaperPromptFor = null) }
        if (!accept) return
        launch {
            when (wallpaper.handOff(t.id)) {
                WallpaperHandoff.CROP_AND_SET, WallpaperHandoff.ATTACH_DATA -> Unit
                WallpaperHandoff.NO_HANDLER -> emit(LibraryMessage.NO_WALLPAPER_APP)
                null -> emit(LibraryMessage.WALLPAPER_FAILED)
            }
        }
    }

    private fun duplicate(id: String) {
        launch { if (manager.duplicate(id) == null) emit(LibraryMessage.DUPLICATE_FAILED) }
    }

    /**
     * Deletes are serialised (§11.3 undo window): a second delete first commits the pending one, and the pending state
     * is set inside the same critical section, so no ticket is lost between two quick deletes.
     */
    private fun delete(id: String) {
        launch {
            deleteMutex.withLock {
                commitPendingLocked()
                val ticket = manager.requestDelete(id) ?: return@withLock
                local.update { it.copy(pendingDelete = ticket) }
                deleteJob =
                    appScope.launch {
                        delay(undoWindowMs)
                        // Claim the ticket under the lock: an Undo after this point is reported as too late.
                        val claimed =
                            deleteMutex.withLock {
                                if (local.value.pendingDelete != ticket) return@withLock false
                                local.update { it.copy(pendingDelete = null) }
                                deleteJob = null
                                true
                            }
                        if (claimed) finishDelete(ticket)
                    }
            }
        }
    }

    /** Commits the pending delete at once (caller holds [deleteMutex]). */
    private fun commitPendingLocked() {
        val ticket = local.value.pendingDelete ?: return
        deleteJob?.cancel()
        deleteJob = null
        local.update { it.copy(pendingDelete = null) }
        appScope.launch { finishDelete(ticket) }
    }

    /** Runs to the end even if the screen goes away; a theme changed inside the window is kept (manager decides). */
    private suspend fun finishDelete(ticket: DeleteTicket) {
        withContext(NonCancellable) { manager.commitDelete(ticket) }
    }

    private fun undoDelete() {
        launch {
            deleteMutex.withLock {
                val ticket = local.value.pendingDelete
                if (ticket == null) {
                    // The window closed and the delete already ran: say so instead of failing silently.
                    emit(LibraryMessage.UNDO_TOO_LATE)
                    return@withLock
                }
                deleteJob?.cancel()
                deleteJob = null
                local.update { it.copy(pendingDelete = null) }
                manager.undoDelete(ticket)
            }
        }
    }

    private fun export(id: String) {
        val t = summary(id) ?: return
        launch {
            if (manager.hasBundleMedia(id)) {
                local.update { it.copy(exportChoiceFor = t) }
            } else {
                exportAs(id, bundle = false)
            }
        }
    }

    private fun exportAs(
        id: String,
        bundle: Boolean,
    ) {
        local.update { it.copy(exportChoiceFor = null) }
        launch {
            val file =
                if (bundle) {
                    when (val r = manager.exportBundle(id)) {
                        is Outcome.Ok -> r.value
                        is Outcome.Err -> return@launch emit(exportError(r.error))
                    }
                } else {
                    manager.exportJson(id) ?: return@launch emit(LibraryMessage.EXPORT_FAILED)
                }
            pendingExport = file
            effectChannel.trySend(LibraryEffect.CreateDocument(file.suggestedName, file.mimeType))
        }
    }

    /** No app handles `ACTION_CREATE_DOCUMENT`: drop the prepared bytes and say so. */
    private fun exportUnavailable() {
        pendingExport = null
        emit(LibraryMessage.EXPORT_FAILED)
    }

    private fun exportError(e: ExportError): LibraryMessage =
        when (e) {
            ExportError.TOO_LARGE -> LibraryMessage.EXPORT_TOO_LARGE
            ExportError.NOT_FOUND -> LibraryMessage.EXPORT_FAILED
        }

    private fun exportTo(uri: String?) {
        val file = pendingExport
        pendingExport = null
        if (uri == null) return
        if (file == null) {
            // The process was recreated while the picker was open: the bytes are gone, the new document is empty.
            emit(LibraryMessage.EXPORT_FAILED)
            return
        }
        launch { emit(if (documents.write(uri, file.bytes)) LibraryMessage.EXPORTED else LibraryMessage.EXPORT_FAILED) }
    }

    private fun share(id: String) {
        launch {
            // Shares the JSON document (single file, most receivers understand it); bundles go through Export.
            val file = manager.exportJson(id)
            val uri = file?.let { sharedFiles.publish(it.suggestedName, it.bytes) }
            val started: LaunchResult? = uri?.let { systemIntents.share(it, file.mimeType, shareTitle) }
            if (started !is Outcome.Ok) emit(LibraryMessage.SHARE_FAILED)
        }
    }

    companion object {
        /** §11.3: delete is undoable for 5 s. */
        const val UNDO_WINDOW_MS = 5_000L
    }
}

/** The theme wallpaper hand-off (FR-THEME-34), shared by the library and the import summary. */
class WallpaperOffer(
    private val manager: ThemeManager,
    private val sharedFiles: SharedFiles,
    private val systemIntents: SystemIntents,
) {
    suspend fun available(themeId: String): Boolean = manager.wallpaperImage(themeId) != null

    /** Copies the image to the share cache and starts the system activity; null when the copy failed. */
    suspend fun handOff(themeId: String): WallpaperHandoff? {
        val image = manager.wallpaperImage(themeId) ?: return null
        val uri = sharedFiles.publish("wallpaper-${image.sha256.take(SHA_PREFIX)}.webp", image.bytes) ?: return null
        return systemIntents.setWallpaper(uri)
    }

    private companion object {
        const val SHA_PREFIX = 12
    }
}
