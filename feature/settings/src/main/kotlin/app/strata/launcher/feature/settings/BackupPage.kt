package app.strata.launcher.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.strata.launcher.core.platformapi.backup.BackupError
import app.strata.launcher.core.platformapi.backup.RestorePreview
import app.strata.launcher.core.ui.Strata
import app.strata.launcher.core.ui.TextButton

object BackupTags {
    const val ROW = "settings_row_backup"
    const val PAGE = "settings_backup_page"
    const val EXPORT = "backup_export"
    const val RESTORE = "backup_restore"
    const val UNDO = "backup_undo"
    const val SHEET = "backup_export_sheet"
    const val WARNING = "backup_export_warning"
    const val INCLUDE_WORK = "backup_include_work"
    const val INCLUDE_LOG = "backup_include_log"
    const val CONFIRM_EXPORT = "backup_export_confirm"
    const val PREVIEW = "backup_preview"
    const val REPLACE = "backup_replace"
    const val CANCEL_PREVIEW = "backup_cancel_preview"
    const val MESSAGE = "backup_message"
    const val SHARE = "backup_share"
    const val BUSY = "backup_busy"
}

/** Settings → Backup (J-11). */
@Composable
internal fun BackupPage(
    state: BackupUiState,
    onAction: (BackupAction) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().safeDrawingPadding().testTag(BackupTags.PAGE)) {
        item { Title(stringResource(R.string.backup_page)) }
        state.busy?.let { busy ->
            item {
                Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag(BackupTags.BUSY)) {
                    Text(
                        text =
                            stringResource(
                                when (busy) {
                                    BackupBusy.EXPORTING -> R.string.backup_busy_exporting
                                    BackupBusy.READING -> R.string.backup_busy_reading
                                    BackupBusy.RESTORING -> R.string.backup_busy_restoring
                                    BackupBusy.UNDOING -> R.string.backup_busy_undoing
                                },
                            ),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
        state.message?.let { m -> item { MessageCard(m, onAction) } }
        item {
            ActionRow(
                title = stringResource(R.string.backup_export),
                summary = stringResource(R.string.backup_export_summary),
                tag = BackupTags.EXPORT,
            ) { if (state.busy == null) onAction(BackupAction.OpenExport) }
        }
        item {
            ActionRow(
                title = stringResource(R.string.backup_restore),
                summary = stringResource(R.string.backup_restore_summary),
                tag = BackupTags.RESTORE,
            ) { if (state.busy == null) onAction(BackupAction.PickRestore) }
        }
        if (state.undoAvailable) {
            item {
                ActionRow(
                    title = stringResource(R.string.backup_undo),
                    summary = stringResource(R.string.backup_undo_summary),
                    tag = BackupTags.UNDO,
                ) { if (state.busy == null) onAction(BackupAction.Undo) }
            }
        }
    }
    if (state.exportSheet) ExportSheet(state, onAction)
    state.preview?.let { PreviewDialog(it, onAction) }
}

@Composable
private fun MessageCard(
    m: BackupMessage,
    onAction: (BackupAction) -> Unit,
) {
    val text =
        when (m) {
            is BackupMessage.Exported -> stringResource(R.string.backup_exported)
            BackupMessage.Restored -> stringResource(R.string.backup_restored)
            BackupMessage.Undone -> stringResource(R.string.backup_undone)
            BackupMessage.NoHandler -> stringResource(R.string.settings_no_handler)
            is BackupMessage.Failed -> errorText(m.error)
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag(BackupTags.MESSAGE),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (m is BackupMessage.Exported) {
            Text(
                text = pluralStringResource(R.plurals.backup_count_favorites, m.counts.favorites, m.counts.favorites),
                style = MaterialTheme.typography.bodyMedium,
                color = Strata.colors.textSecondary,
            )
            Text(
                text = pluralStringResource(R.plurals.backup_count_themes, m.counts.themes, m.counts.themes),
                style = MaterialTheme.typography.bodyMedium,
                color = Strata.colors.textSecondary,
            )
        }
        Row {
            if (m is BackupMessage.Exported) {
                TextButton(onClick = { onAction(BackupAction.Share) }, modifier = Modifier.testTag(BackupTags.SHARE)) {
                    Text(stringResource(R.string.backup_share))
                }
            }
            TextButton(
                onClick = { onAction(BackupAction.DismissMessage) },
            ) { Text(stringResource(R.string.backup_dismiss)) }
        }
    }
}

@Composable
internal fun errorText(e: BackupError): String =
    when (e) {
        BackupError.NotABackup -> {
            stringResource(R.string.backup_error_not_backup)
        }

        is BackupError.Damaged -> {
            if (e.line > 0) {
                stringResource(R.string.backup_error_damaged_at, e.line, e.column)
            } else {
                stringResource(R.string.backup_error_damaged)
            }
        }

        BackupError.TooLarge -> {
            stringResource(R.string.backup_error_too_large)
        }

        BackupError.NewerVersion -> {
            stringResource(R.string.backup_error_newer)
        }

        BackupError.Unreadable -> {
            stringResource(R.string.backup_error_unreadable)
        }

        BackupError.WriteFailed -> {
            stringResource(R.string.backup_error_write)
        }

        BackupError.NotReady -> {
            stringResource(R.string.backup_error_not_ready)
        }

        BackupError.Expired -> {
            stringResource(R.string.backup_error_expired)
        }

        BackupError.RestoreFailed -> {
            stringResource(R.string.backup_error_failed)
        }

        BackupError.RestoreIncomplete -> {
            stringResource(R.string.backup_error_incomplete)
        }

        BackupError.NothingToUndo -> {
            stringResource(R.string.backup_error_nothing_to_undo)
        }
    }

/** ARCHITECTURE §12.2: what is included, the opt-ins, and the privacy warning, before the system "save as" screen. */
@Composable
private fun ExportSheet(
    state: BackupUiState,
    onAction: (BackupAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(BackupAction.DismissExport) },
        title = { Text(stringResource(R.string.backup_export_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.backup_export_warning),
                    modifier = Modifier.padding(bottom = 8.dp).testTag(BackupTags.WARNING),
                )
                Text(
                    text = stringResource(R.string.backup_export_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Strata.colors.textSecondary,
                )
                if (state.workProfile) {
                    SwitchRow(
                        title = stringResource(R.string.backup_include_work),
                        summary = null,
                        checked = state.includeWork,
                        onChange = { onAction(BackupAction.IncludeWork(it)) },
                        tag = BackupTags.INCLUDE_WORK,
                    )
                }
                SwitchRow(
                    title = stringResource(R.string.backup_include_log),
                    summary = stringResource(R.string.backup_include_log_summary),
                    checked = state.includeLog,
                    onChange = { onAction(BackupAction.IncludeLog(it)) },
                    tag = BackupTags.INCLUDE_LOG,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAction(BackupAction.ConfirmExport) },
                modifier = Modifier.testTag(BackupTags.CONFIRM_EXPORT),
            ) { Text(stringResource(R.string.backup_export_confirm)) }
        },
        dismissButton = {
            TextButton(
                onClick = { onAction(BackupAction.DismissExport) },
            ) { Text(stringResource(R.string.backup_cancel)) }
        },
        modifier = Modifier.testTag(BackupTags.SHEET),
    )
}

/** ARCHITECTURE §12.3 step 2: counts, apps not installed, widgets to re-add; nothing is written yet. */
@Composable
private fun PreviewDialog(
    p: RestorePreview,
    onAction: (BackupAction) -> Unit,
) {
    val c = p.counts
    val lines =
        buildList {
            add(pluralStringResource(R.plurals.backup_count_settings, c.settings, c.settings))
            add(pluralStringResource(R.plurals.backup_count_favorites, c.favorites, c.favorites))
            add(pluralStringResource(R.plurals.backup_count_hidden, c.hidden, c.hidden))
            add(pluralStringResource(R.plurals.backup_count_renames, c.renames, c.renames))
            add(pluralStringResource(R.plurals.backup_count_icons, c.iconOverrides, c.iconOverrides))
            add(pluralStringResource(R.plurals.backup_count_themes, c.themes, c.themes))
            add(pluralStringResource(R.plurals.backup_count_widgets, p.widgetsToReAdd, p.widgetsToReAdd))
            if (c.launchLogDays > 0) {
                add(pluralStringResource(R.plurals.backup_count_log_days, c.launchLogDays, c.launchLogDays))
            }
            if (p.missingApps.isNotEmpty()) {
                add(pluralStringResource(R.plurals.backup_count_missing, p.missingApps.size, p.missingApps.size))
            }
            if (p.newerSettingsIgnored > 0) {
                add(pluralStringResource(R.plurals.backup_count_newer, p.newerSettingsIgnored, p.newerSettingsIgnored))
            }
            if (p.droppedEntries > 0) {
                add(pluralStringResource(R.plurals.backup_count_dropped, p.droppedEntries, p.droppedEntries))
            }
            if (p.workSkipped > 0) {
                add(pluralStringResource(R.plurals.backup_count_work_skipped, p.workSkipped, p.workSkipped))
            }
            p.excludedThemes.forEach { add(stringResource(R.string.backup_theme_excluded, it)) }
            p.needsUpdateThemes.forEach { add(stringResource(R.string.backup_theme_needs_update, it)) }
        }
    AlertDialog(
        onDismissRequest = { onAction(BackupAction.CancelPreview) },
        title = { Text(stringResource(R.string.backup_preview_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 480.dp)) {
                p.createdAt?.let { Text(stringResource(R.string.backup_preview_created, it.take(10))) }
                lines.forEach { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
                if (p.missingApps.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.backup_preview_missing_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    p.missingApps.forEach {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Strata.colors.textSecondary,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.backup_preview_replace_note),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(BackupAction.Replace) }, modifier = Modifier.testTag(BackupTags.REPLACE)) {
                // Destructive style (J-11).
                Text(stringResource(R.string.backup_replace), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onAction(BackupAction.CancelPreview) },
                modifier = Modifier.testTag(BackupTags.CANCEL_PREVIEW),
            ) { Text(stringResource(R.string.backup_cancel)) }
        },
        modifier = Modifier.testTag(BackupTags.PREVIEW),
    )
}
