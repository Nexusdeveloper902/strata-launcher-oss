package app.strata.launcher.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.strata.launcher.core.ui.Strata

object DiagnosticTags {
    const val ROW = "settings_diagnostic_log"
    const val PREVIEW = "settings_diagnostic_preview"
    const val PREVIEW_TEXT = "settings_diagnostic_preview_text"
    const val SAVE = "settings_diagnostic_save"
    const val MESSAGE = "settings_diagnostic_message"
    const val LANGUAGE_ROW = "settings_app_language"
}

/** The preview is shown up to this many characters; the saved file has everything. */
private const val PREVIEW_CHARS = 20_000

/** The About rows for the diagnostic log: the action row and its result line. */
@Composable
internal fun DiagnosticLogRows(
    state: DiagnosticLogUiState,
    onAction: (DiagnosticAction) -> Unit,
) {
    Column {
        ActionRow(
            title = stringResource(R.string.diagnostic_row),
            summary = stringResource(R.string.diagnostic_row_summary),
            tag = DiagnosticTags.ROW,
        ) { onAction(DiagnosticAction.Open) }
        val message = state.message
        if (message != null) {
            val text =
                when (message) {
                    DiagnosticMessage.SAVED -> R.string.diagnostic_saved
                    DiagnosticMessage.FAILED -> R.string.diagnostic_failed
                    DiagnosticMessage.NO_HANDLER -> R.string.settings_no_handler
                }
            Text(
                text = stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .testTag(DiagnosticTags.MESSAGE)
                        .semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else if (state.busy && !state.open) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        }
    }
    if (state.open) DiagnosticPreview(state, onAction)
}

@Composable
private fun DiagnosticPreview(
    state: DiagnosticLogUiState,
    onAction: (DiagnosticAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(DiagnosticAction.Dismiss) },
        modifier = Modifier.testTag(DiagnosticTags.PREVIEW),
        title = { Text(stringResource(R.string.diagnostic_preview_title)) },
        text = {
            Column {
                Text(stringResource(R.string.diagnostic_preview_body), style = MaterialTheme.typography.bodyMedium)
                val text = state.text
                if (text == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                } else {
                    Text(
                        text = if (text.length > PREVIEW_CHARS) text.take(PREVIEW_CHARS) + "\n…" else text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = Strata.colors.textSecondary,
                        modifier =
                            Modifier
                                .padding(top = 12.dp)
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState())
                                .testTag(DiagnosticTags.PREVIEW_TEXT),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAction(DiagnosticAction.Save) },
                enabled = state.text != null,
                modifier = Modifier.testTag(DiagnosticTags.SAVE),
            ) { Text(stringResource(R.string.diagnostic_save)) }
        },
        dismissButton = {
            TextButton(
                onClick = { onAction(DiagnosticAction.Dismiss) },
            ) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}
