package app.strata.launcher.feature.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.platformapi.system.DocumentIO
import app.strata.launcher.core.platformapi.system.DocumentRead
import app.strata.launcher.core.platformapi.system.SharedFiles
import app.strata.launcher.core.platformapi.system.SystemIntents
import app.strata.launcher.core.platformapi.system.WallpaperHandoff
import app.strata.launcher.core.theme.importing.ConflictChoice
import app.strata.launcher.core.theme.importing.ImportConflict
import app.strata.launcher.core.theme.importing.ImportError
import app.strata.launcher.core.theme.importing.ImportMessage
import app.strata.launcher.core.theme.importing.ZipLimits
import app.strata.launcher.core.theme.store.ImportCommit
import app.strata.launcher.core.theme.store.ImportPreview
import app.strata.launcher.core.theme.store.ThemeManager
import app.strata.launcher.core.theme.store.ThemeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The import screen (THEME_SYSTEM §10.4): load → summary → (conflict question) → done. */
sealed interface ImportUiState {
    data object Loading : ImportUiState

    /** [line]/[column]: where a damaged JSON file failed to parse (§8.2 "line 12, column 4"). */
    data class Failed(
        val message: ImportMessage,
        val line: Int? = null,
        val column: Int? = null,
    ) : ImportUiState

    data class Summary(
        val preview: ImportPreview,
        /** Same id, different content: Replace / Keep both / Cancel is open. */
        val askingConflict: Boolean = false,
        /** The user chose "Add and apply" (kept while the conflict question is open). */
        val applyRequested: Boolean = false,
        val working: Boolean = false,
    ) : ImportUiState

    data class Done(
        val commit: ImportCommit,
        val name: String,
        /** "Also set this theme's wallpaper?" (only after applying a theme that carries one). */
        val askWallpaper: Boolean,
        val wallpaperResult: WallpaperHandoff? = null,
    ) : ImportUiState
}

sealed interface ImportAction {
    /** "Add" ([apply] false) or "Add and apply". For an identical theme, "Apply". */
    data class Add(val apply: Boolean) : ImportAction

    data class Resolve(val choice: ConflictChoice) : ImportAction

    data object CancelConflict : ImportAction

    data object UndoReplace : ImportAction

    data class Wallpaper(val accept: Boolean) : ImportAction

    data object Cancel : ImportAction
}

/**
 * Import of one document (FR-THEME-21…26): bounded read of a `content:` URI (anything else is refused unread,
 * T-SEC-01), sniffing and validation through [ThemeManager.prepareImport], the summary, the conflict question for a
 * same-id theme, then Add / Add and apply. Nothing is applied before the user confirms.
 */
class ThemeImportViewModel(
    private val manager: ThemeManager,
    private val themes: ThemeRepository,
    private val documents: DocumentIO,
    sharedFiles: SharedFiles,
    systemIntents: SystemIntents,
) : ViewModel() {
    private val stateFlow = MutableStateFlow<ImportUiState>(ImportUiState.Loading)
    val state: StateFlow<ImportUiState> = stateFlow.asStateFlow()

    /** Set when the screen is finished (cancelled, or done without a pending question). */
    private val finishedFlow = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = finishedFlow.asStateFlow()
    private val wallpaper = WallpaperOffer(manager, sharedFiles, systemIntents)
    private var started = false

    /** Starts the import of [uri]; null when the intent carried no usable URI. Idempotent per instance. */
    fun load(uri: String?) {
        if (started) return
        started = true
        themes.start()
        if (uri == null) {
            stateFlow.value = ImportUiState.Failed(ImportMessage.NOT_A_THEME)
            return
        }
        viewModelScope.launch {
            val bytes =
                when (val r = documents.read(uri, MAX_SOURCE_BYTES)) {
                    is DocumentRead.Ok -> r.bytes
                    DocumentRead.TooLarge -> return@launch fail(ImportError.TOO_LARGE)
                    DocumentRead.UnsupportedScheme -> return@launch fail(ImportError.UNSUPPORTED_SOURCE)
                    DocumentRead.Failed -> return@launch fail(ImportError.UNREADABLE)
                }
            stateFlow.value =
                when (val r = manager.prepareImport(bytes)) {
                    is Outcome.Ok -> ImportUiState.Summary(r.value)
                    is Outcome.Err -> failed(r.error)
                }
        }
    }

    private fun failed(e: ImportError): ImportUiState.Failed {
        val issue = (e as? ImportError.Theme)?.error?.issue
        return ImportUiState.Failed(e.message, issue?.line, issue?.column)
    }

    /** The source was refused before reading (e.g. a non-content URI from another app). */
    fun refuse(message: ImportMessage) {
        started = true
        stateFlow.value = ImportUiState.Failed(message)
    }

    private fun fail(e: ImportError) {
        stateFlow.value = failed(e)
    }

    fun onAction(action: ImportAction) {
        when (action) {
            is ImportAction.Add -> {
                add(action.apply)
            }

            is ImportAction.Resolve -> {
                resolve(action.choice)
            }

            ImportAction.CancelConflict -> {
                stateFlow.update {
                    (it as? ImportUiState.Summary)?.copy(askingConflict = false)
                        ?: it
                }
            }

            ImportAction.UndoReplace -> {
                undoReplace()
            }

            is ImportAction.Wallpaper -> {
                wallpaperAnswer(action.accept)
            }

            ImportAction.Cancel -> {
                cancel()
            }
        }
    }

    private fun add(apply: Boolean) {
        val s = stateFlow.value as? ImportUiState.Summary ?: return
        if (s.working) return
        if (s.preview.conflict == ImportConflict.SAME_ID) {
            stateFlow.value = s.copy(askingConflict = true, applyRequested = apply)
            return
        }
        commit(s, ConflictChoice.KEEP_BOTH, apply)
    }

    private fun resolve(choice: ConflictChoice) {
        val s = stateFlow.value as? ImportUiState.Summary ?: return
        commit(s.copy(askingConflict = false), choice, s.applyRequested)
    }

    private fun commit(
        s: ImportUiState.Summary,
        choice: ConflictChoice,
        apply: Boolean,
    ) {
        stateFlow.value = s.copy(askingConflict = false, working = true)
        viewModelScope.launch {
            when (val r = manager.commitImport(s.preview.token, choice, apply)) {
                is Outcome.Ok -> {
                    val name =
                        if (choice == ConflictChoice.REPLACE) s.preview.replaceName else s.preview.displayName
                    val ask = r.value.applied && wallpaper.available(r.value.id)
                    stateFlow.value = ImportUiState.Done(r.value, name, askWallpaper = ask)
                }

                is Outcome.Err -> {
                    stateFlow.value = failed(r.error)
                }
            }
        }
    }

    private fun undoReplace() {
        val d = stateFlow.value as? ImportUiState.Done ?: return
        if (!d.commit.replaced) return
        viewModelScope.launch {
            manager.undoReplace(d.commit.id)
            finishedFlow.value = true
        }
    }

    private fun wallpaperAnswer(accept: Boolean) {
        val d = stateFlow.value as? ImportUiState.Done ?: return
        stateFlow.value = d.copy(askWallpaper = false)
        if (!accept) return
        viewModelScope.launch {
            val result = wallpaper.handOff(d.commit.id) ?: WallpaperHandoff.NO_HANDLER
            stateFlow.update { (it as? ImportUiState.Done)?.copy(wallpaperResult = result) ?: it }
        }
    }

    private fun cancel() {
        (stateFlow.value as? ImportUiState.Summary)?.let { manager.discardImport(it.preview.token) }
        finishedFlow.value = true
    }

    override fun onCleared() {
        (stateFlow.value as? ImportUiState.Summary)?.let { manager.discardImport(it.preview.token) }
        super.onCleared()
    }

    companion object {
        /** The archive limit of §10.3.3 (a JSON theme is capped at 256 KiB by the parser itself). */
        const val MAX_SOURCE_BYTES = 24 * ZipLimits.MIB
    }
}
