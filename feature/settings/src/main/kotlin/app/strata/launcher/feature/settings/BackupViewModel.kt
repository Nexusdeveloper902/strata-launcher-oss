package app.strata.launcher.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.platformapi.backup.BackupCounts
import app.strata.launcher.core.platformapi.backup.BackupError
import app.strata.launcher.core.platformapi.backup.BackupService
import app.strata.launcher.core.platformapi.backup.ExportOptions
import app.strata.launcher.core.platformapi.backup.RestorePreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Backup page is doing (J-11). */
enum class BackupBusy { EXPORTING, READING, RESTORING, UNDOING }

/** The result line of the Backup page (a polite live region, INTERACTION_SPEC §… announcements). */
sealed interface BackupMessage {
    data class Exported(
        val counts: BackupCounts,
        /** The written document, for "Share". */
        val uri: String,
    ) : BackupMessage

    data object Restored : BackupMessage

    data object Undone : BackupMessage

    /** No document provider (or share target) could be started. */
    data object NoHandler : BackupMessage

    data class Failed(
        val error: BackupError,
    ) : BackupMessage
}

data class BackupUiState(
    /** The export sheet with the privacy warning and the two opt-ins (ARCHITECTURE §12.2). */
    val exportSheet: Boolean = false,
    val includeWork: Boolean = false,
    val includeLog: Boolean = false,
    /** A work profile exists, so "Include work profile" is offered. */
    val workProfile: Boolean = false,
    val busy: BackupBusy? = null,
    /** The validated file waiting for "Replace current setup" (§12.3 step 2). */
    val preview: RestorePreview? = null,
    val message: BackupMessage? = null,
    val undoAvailable: Boolean = false,
)

sealed interface BackupAction {
    data object OpenExport : BackupAction

    data object DismissExport : BackupAction

    data class IncludeWork(
        val on: Boolean,
    ) : BackupAction

    data class IncludeLog(
        val on: Boolean,
    ) : BackupAction

    /** "Export" in the sheet: the system "save as" screen opens ([BackupEffect.CreateDocument]). */
    data object ConfirmExport : BackupAction

    /** The `ACTION_CREATE_DOCUMENT` result; null = cancelled. Acted on only when this view model asked for it. */
    data class ExportTo(
        val uri: String?,
    ) : BackupAction

    /** No document provider could be started. */
    data object PickerUnavailable : BackupAction

    data object PickRestore : BackupAction

    /** The `ACTION_OPEN_DOCUMENT` result; null = cancelled. */
    data class RestoreFrom(
        val uri: String?,
    ) : BackupAction

    /** "Replace current setup". */
    data object Replace : BackupAction

    data object CancelPreview : BackupAction

    data object Undo : BackupAction

    data object Share : BackupAction

    data object DismissMessage : BackupAction
}

sealed interface BackupEffect {
    data class CreateDocument(
        val suggestedName: String,
    ) : BackupEffect

    data object OpenDocument : BackupEffect

    data class ShareDocument(
        val uri: String,
    ) : BackupEffect
}

/**
 * Settings → Backup (FR-BACKUP-01…03, J-11): export with the privacy warning, restore as validate → preview → replace,
 * and "Undo restore". The document picker in flight and the chosen opt-ins live in the [SavedStateHandle], so a result
 * that arrives after the activity (or the process) was recreated is still acted on, and a stray result is ignored.
 * Every service call is main-safe; nothing here touches the disk.
 */
class BackupViewModel(
    private val service: BackupService,
    private val logger: Logger,
    private val handle: SavedStateHandle,
    hasWorkProfile: Flow<Boolean> = flowOf(false),
) : ViewModel() {
    private val local =
        MutableStateFlow(
            BackupUiState(
                includeWork = handle[KEY_WORK] ?: false,
                includeLog = handle[KEY_LOG] ?: false,
            ),
        )
    private val effectChannel = Channel<BackupEffect>(Channel.BUFFERED)
    val effects: Flow<BackupEffect> = effectChannel.receiveAsFlow()

    val state: StateFlow<BackupUiState> =
        combine(local, service.undoAvailable, hasWorkProfile.catch { emit(false) }) { l, undo, work ->
            l.copy(undoAvailable = undo, workProfile = work, includeWork = l.includeWork && work)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    fun onAction(action: BackupAction) {
        when (action) {
            BackupAction.OpenExport -> {
                local.update { it.copy(exportSheet = true, message = null) }
            }

            BackupAction.DismissExport -> {
                local.update { it.copy(exportSheet = false) }
            }

            is BackupAction.IncludeWork -> {
                handle[KEY_WORK] = action.on
                local.update { it.copy(includeWork = action.on) }
            }

            is BackupAction.IncludeLog -> {
                handle[KEY_LOG] = action.on
                local.update { it.copy(includeLog = action.on) }
            }

            BackupAction.ConfirmExport -> {
                handle[KEY_PENDING] = PENDING_EXPORT
                handle[KEY_WORK] = state.value.includeWork
                handle[KEY_LOG] = local.value.includeLog
                local.update { it.copy(exportSheet = false) }
                effectChannel.trySend(BackupEffect.CreateDocument(service.suggestedFileName()))
            }

            is BackupAction.ExportTo -> {
                exportTo(action.uri)
            }

            BackupAction.PickerUnavailable -> {
                handle.remove<String>(KEY_PENDING)
                local.update { it.copy(message = BackupMessage.NoHandler) }
            }

            BackupAction.PickRestore -> {
                handle[KEY_PENDING] = PENDING_RESTORE
                local.update { it.copy(message = null) }
                effectChannel.trySend(BackupEffect.OpenDocument)
            }

            is BackupAction.RestoreFrom -> {
                restoreFrom(action.uri)
            }

            BackupAction.Replace -> {
                replace()
            }

            BackupAction.CancelPreview -> {
                local.value.preview?.let { service.discard(it.token) }
                local.update { it.copy(preview = null) }
            }

            BackupAction.Undo -> {
                undo()
            }

            BackupAction.Share -> {
                (local.value.message as? BackupMessage.Exported)?.let {
                    effectChannel.trySend(BackupEffect.ShareDocument(it.uri))
                }
            }

            BackupAction.DismissMessage -> {
                local.update { it.copy(message = null) }
            }
        }
    }

    private fun exportTo(uri: String?) {
        if (handle.get<String>(KEY_PENDING) != PENDING_EXPORT) return
        handle.remove<String>(KEY_PENDING)
        if (uri == null) return
        val options = ExportOptions(workProfile = handle[KEY_WORK] ?: false, launchLog = handle[KEY_LOG] ?: false)
        run(BackupBusy.EXPORTING) {
            when (val r = service.export(uri, options)) {
                is Outcome.Ok -> local.update { it.copy(message = BackupMessage.Exported(r.value.counts, uri)) }
                is Outcome.Err -> local.update { it.copy(message = BackupMessage.Failed(r.error)) }
            }
        }
    }

    private fun restoreFrom(uri: String?) {
        if (handle.get<String>(KEY_PENDING) != PENDING_RESTORE) return
        handle.remove<String>(KEY_PENDING)
        if (uri == null) return
        run(BackupBusy.READING) {
            when (val r = service.preview(uri)) {
                is Outcome.Ok -> local.update { it.copy(preview = r.value) }
                is Outcome.Err -> local.update { it.copy(message = BackupMessage.Failed(r.error)) }
            }
        }
    }

    private fun replace() {
        if (local.value.busy != null) return
        val p = local.value.preview ?: return
        local.update { it.copy(preview = null) }
        run(BackupBusy.RESTORING) {
            when (val r = service.restore(p.token)) {
                is Outcome.Ok -> local.update { it.copy(message = BackupMessage.Restored) }
                is Outcome.Err -> local.update { it.copy(message = BackupMessage.Failed(r.error)) }
            }
        }
    }

    private fun undo() {
        run(BackupBusy.UNDOING) {
            when (val r = service.undoRestore()) {
                is Outcome.Ok -> local.update { it.copy(message = BackupMessage.Undone) }
                is Outcome.Err -> local.update { it.copy(message = BackupMessage.Failed(r.error)) }
            }
        }
    }

    /** One operation at a time; failures that escape the service become a message, never a crash. */
    private fun run(
        busy: BackupBusy,
        block: suspend () -> Unit,
    ) {
        if (local.value.busy != null) return
        local.update { it.copy(busy = busy) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "backup action failed", e)
                local.update { it.copy(message = BackupMessage.Failed(BackupError.Unreadable)) }
            } finally {
                local.update { it.copy(busy = null) }
            }
        }
    }

    override fun onCleared() {
        local.value.preview?.let { service.discard(it.token) }
    }

    private companion object {
        const val TAG = "BackupVM"
        const val KEY_PENDING = "backup_pending_picker"
        const val KEY_WORK = "backup_include_work"
        const val KEY_LOG = "backup_include_log"
        const val PENDING_EXPORT = "export"
        const val PENDING_RESTORE = "restore"
    }
}
