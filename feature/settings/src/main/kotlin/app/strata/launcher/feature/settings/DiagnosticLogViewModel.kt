package app.strata.launcher.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.platformapi.system.DiagnosticLogService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The result line under About -> "Save diagnostic log" (a polite live region). */
enum class DiagnosticMessage { SAVED, FAILED, NO_HANDLER }

data class DiagnosticLogUiState(
    /** The preview dialog is open. */
    val open: Boolean = false,
    /** The text shown in the preview and saved; null while it is built. */
    val text: String? = null,
    val busy: Boolean = false,
    val message: DiagnosticMessage? = null,
)

sealed interface DiagnosticAction {
    /** "Save diagnostic log": builds the redacted text and shows the preview. */
    data object Open : DiagnosticAction

    data object Dismiss : DiagnosticAction

    /** "Save" in the preview: the system "save as" screen opens. */
    data object Save : DiagnosticAction

    /** The `ACTION_CREATE_DOCUMENT` result; null = cancelled. Acted on only when this view model asked for it. */
    data class SaveTo(
        val uri: String?,
    ) : DiagnosticAction

    data object PickerUnavailable : DiagnosticAction

    data object DismissMessage : DiagnosticAction
}

sealed interface DiagnosticEffect {
    data class CreateDocument(
        val suggestedName: String,
    ) : DiagnosticEffect
}

/**
 * About -> "Save diagnostic log" (ARCHITECTURE §11): preview first, then the system "save as" screen. The picker in
 * flight lives in the [SavedStateHandle]; the text does not (it can be large), so a result that arrives after process
 * death builds it again. Nothing here touches the disk.
 */
class DiagnosticLogViewModel(
    private val service: DiagnosticLogService,
    private val logger: Logger,
    private val handle: SavedStateHandle,
) : ViewModel() {
    private val mutable = MutableStateFlow(DiagnosticLogUiState())
    val state: StateFlow<DiagnosticLogUiState> = mutable.asStateFlow()
    private val effectChannel = Channel<DiagnosticEffect>(Channel.BUFFERED)
    val effects: Flow<DiagnosticEffect> = effectChannel.receiveAsFlow()

    fun onAction(action: DiagnosticAction) {
        when (action) {
            DiagnosticAction.Open -> {
                mutable.update { it.copy(open = true, text = null, busy = true, message = null) }
                viewModelScope.launch {
                    val text = safely { service.build() }
                    mutable.update {
                        if (text == null) {
                            it.copy(open = false, busy = false, message = DiagnosticMessage.FAILED)
                        } else {
                            it.copy(text = text, busy = false)
                        }
                    }
                }
            }

            DiagnosticAction.Dismiss -> {
                mutable.update { it.copy(open = false, text = null) }
            }

            DiagnosticAction.Save -> {
                if (mutable.value.text == null) return
                handle[KEY_PICKING] = true
                mutable.update { it.copy(open = false) }
                effectChannel.trySend(DiagnosticEffect.CreateDocument(service.suggestedFileName()))
            }

            is DiagnosticAction.SaveTo -> {
                saveTo(action.uri)
            }

            DiagnosticAction.PickerUnavailable -> {
                handle[KEY_PICKING] = false
                mutable.update { it.copy(open = false, text = null, message = DiagnosticMessage.NO_HANDLER) }
            }

            DiagnosticAction.DismissMessage -> {
                mutable.update { it.copy(message = null) }
            }
        }
    }

    private fun saveTo(uri: String?) {
        if (handle.get<Boolean>(KEY_PICKING) != true) return
        handle[KEY_PICKING] = false
        if (uri == null) {
            mutable.update { it.copy(text = null) }
            return
        }
        val kept = mutable.value.text
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            val text = kept ?: safely { service.build() }
            val ok = text != null && safely { service.save(uri, text) } == true
            mutable.update {
                it.copy(
                    text = null,
                    busy = false,
                    message = if (ok) DiagnosticMessage.SAVED else DiagnosticMessage.FAILED,
                )
            }
        }
    }

    private suspend fun <T> safely(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "diagnostic log failed", e)
            null
        } catch (e: OutOfMemoryError) {
            logger.w(TAG, "diagnostic log too large", e)
            null
        }

    private companion object {
        const val TAG = "DiagnosticLog"
        const val KEY_PICKING = "diagnostic_picking"
    }
}
