package app.strata.launcher.feature.theme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.strata.launcher.core.platformapi.system.WallpaperHandoff
import app.strata.launcher.core.theme.contrast.ContrastStatus
import app.strata.launcher.core.theme.importing.ConflictChoice
import app.strata.launcher.core.theme.importing.ImportConflict
import app.strata.launcher.core.theme.importing.ImportMessage
import app.strata.launcher.core.theme.store.ImportPreview
import app.strata.launcher.core.ui.Button
import app.strata.launcher.core.ui.DialogText
import app.strata.launcher.core.ui.OutlinedButton
import app.strata.launcher.core.ui.TextButton

/** The import summary (THEME_SYSTEM §10.4): nothing is written or applied before Add / Add and apply. */
@Composable
fun ThemeImportScreen(
    state: ImportUiState,
    onAction: (ImportAction) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler {
        if (state is ImportUiState.Summary) onAction(ImportAction.Cancel) else onClose()
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .testTag(ThemeTestTags.IMPORT_SCREEN),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.theme_import_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            when (state) {
                ImportUiState.Loading -> Loading()
                is ImportUiState.Failed -> Failed(state, onClose)
                is ImportUiState.Summary -> Summary(state, onAction)
                is ImportUiState.Done -> Done(state, onAction, onClose)
            }
        }
        if (state is ImportUiState.Summary && state.askingConflict) ConflictDialog(state.preview, onAction)
        if (state is ImportUiState.Done && state.askWallpaper) {
            WallpaperPrompt { onAction(ImportAction.Wallpaper(it)) }
        }
    }
}

@Composable
private fun Loading() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text(stringResource(R.string.theme_import_loading), color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun Failed(
    state: ImportUiState.Failed,
    onClose: () -> Unit,
) {
    val text =
        if (state.message == ImportMessage.DAMAGED && state.line != null && state.column != null) {
            stringResource(R.string.theme_error_damaged_at, state.line, state.column)
        } else {
            stringResource(errorText(state.message))
        }
    Text(
        text,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.testTag(ThemeTestTags.IMPORT_ERROR).semantics { liveRegion = LiveRegionMode.Polite },
    )
    Button(onClick = onClose, modifier = Modifier.testTag(ThemeTestTags.IMPORT_CLOSE)) {
        Text(stringResource(R.string.theme_import_close))
    }
}

fun errorText(m: ImportMessage): Int =
    when (m) {
        ImportMessage.NOT_A_THEME -> R.string.theme_error_not_a_theme
        ImportMessage.NEWER_VERSION -> R.string.theme_error_newer
        ImportMessage.DAMAGED -> R.string.theme_error_damaged
        ImportMessage.TOO_LARGE -> R.string.theme_error_too_large
        ImportMessage.UNEXPECTED_FILES -> R.string.theme_error_unexpected_files
        ImportMessage.FONT_UNREADABLE -> R.string.theme_error_font
        ImportMessage.IMAGE_UNREADABLE -> R.string.theme_error_image
        ImportMessage.UNREADABLE -> R.string.theme_error_unreadable
        ImportMessage.NOT_ENOUGH_STORAGE -> R.string.theme_error_storage
        ImportMessage.UNSUPPORTED_SOURCE -> R.string.theme_error_source
    }

@Composable
private fun Summary(
    state: ImportUiState.Summary,
    onAction: (ImportAction) -> Unit,
) {
    val p = state.preview
    Preview(p)
    Text(p.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
    p.author?.let { Note(stringResource(R.string.theme_import_by, it)) }
    p.description?.let { Note(it) }
    when (p.conflict) {
        ImportConflict.IDENTICAL -> {
            Note(stringResource(R.string.theme_import_already), ThemeTestTags.IMPORT_NOTE)
        }

        ImportConflict.BUILT_IN_ID -> {
            Note(
                stringResource(R.string.theme_import_builtin_copy),
                ThemeTestTags.IMPORT_NOTE,
            )
        }

        ImportConflict.SAME_ID, ImportConflict.NONE -> {
            Unit
        }
    }
    if (p.conflict != ImportConflict.IDENTICAL && p.displayName != p.name) {
        Note(stringResource(R.string.theme_import_renamed, p.displayName), ThemeTestTags.IMPORT_NOTE)
    }
    ContrastLine(p.contrast)
    if (p.newerSettingsKept > 0) {
        Note(
            pluralStringResource(R.plurals.theme_import_newer_settings, p.newerSettingsKept, p.newerSettingsKept),
            ThemeTestTags.IMPORT_NEWER,
        )
    }
    if (p.missingFonts > 0) {
        Note(pluralStringResource(R.plurals.theme_import_missing_fonts, p.missingFonts, p.missingFonts))
    }
    p.missingIconPack?.let {
        Note(stringResource(R.string.theme_import_missing_pack, it.displayName ?: it.packageName))
    }
    if (p.fromBundle && (p.hasWallpaperImage || p.missingFonts == 0)) Note(stringResource(R.string.theme_import_bundle))
    Spacer(Modifier.height(8.dp))
    if (p.conflict == ImportConflict.IDENTICAL) {
        Button(
            onClick = { onAction(ImportAction.Add(apply = true)) },
            enabled = !state.working,
            modifier = Modifier.fillMaxWidth().testTag(ThemeTestTags.IMPORT_ADD_APPLY),
        ) { Text(stringResource(R.string.theme_apply)) }
    } else {
        Button(
            onClick = { onAction(ImportAction.Add(apply = true)) },
            enabled = !state.working,
            modifier = Modifier.fillMaxWidth().testTag(ThemeTestTags.IMPORT_ADD_APPLY),
        ) { Text(stringResource(R.string.theme_import_add_apply)) }
        OutlinedButton(
            onClick = { onAction(ImportAction.Add(apply = false)) },
            enabled = !state.working,
            modifier = Modifier.fillMaxWidth().testTag(ThemeTestTags.IMPORT_ADD),
        ) { Text(stringResource(R.string.theme_import_add)) }
    }
    TextButton(
        onClick = { onAction(ImportAction.Cancel) },
        modifier = Modifier.fillMaxWidth().testTag(ThemeTestTags.IMPORT_CLOSE),
    ) { Text(stringResource(R.string.theme_cancel)) }
}

/** Contrast status in words and with an icon glyph, never by colour alone (§7.6, §7.7). */
@Composable
private fun ContrastLine(status: ContrastStatus) {
    val (glyph, text) =
        when (status) {
            ContrastStatus.AA_ANY_WALLPAPER -> "✓" to R.string.theme_import_contrast_any
            ContrastStatus.AA_CURRENT_WALLPAPER -> "✓" to R.string.theme_import_contrast_current
            ContrastStatus.LOW_CONTRAST -> "!" to R.string.theme_import_contrast_low
        }
    val words = stringResource(text)
    Row(
        modifier = Modifier.testTag(ThemeTestTags.IMPORT_CONTRAST).semantics { contentDescription = words },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(glyph, color = MaterialTheme.colorScheme.onBackground)
        Text(words, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun Note(
    text: String,
    tag: String? = null,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
    )
}

/** Thumbnail in the theme's own colours (background, a surface pill, primary and secondary text, accent). */
@Composable
private fun Preview(p: ImportPreview) {
    val t = p.preview.forSystemDark(isSystemInDarkTheme())
    val c = t.colors
    val label = stringResource(R.string.theme_preview_label, p.name)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(c.background))
                // One node for TalkBack: the sample texts inside are decoration.
                .clearAndSetSemantics { contentDescription = label }
                .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.theme_preview_sample), color = Color(c.textPrimary))
            Text(stringResource(R.string.theme_preview_secondary), color = Color(c.textSecondary))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(c.accent))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) { Text(stringResource(R.string.theme_apply), color = Color(c.onAccent)) }
        }
    }
}

@Composable
private fun ConflictDialog(
    p: ImportPreview,
    onAction: (ImportAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(ImportAction.CancelConflict) },
        modifier = Modifier.testTag(ThemeTestTags.IMPORT_CONFLICT),
        title = { Text(stringResource(R.string.theme_import_conflict_title, p.replaceName)) },
        text = { DialogText(stringResource(R.string.theme_import_conflict_body)) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    onClick = { onAction(ImportAction.Resolve(ConflictChoice.REPLACE)) },
                    modifier = Modifier.testTag(ThemeTestTags.IMPORT_REPLACE),
                ) { Text(stringResource(R.string.theme_import_replace)) }
                TextButton(
                    onClick = { onAction(ImportAction.Resolve(ConflictChoice.KEEP_BOTH)) },
                    modifier = Modifier.testTag(ThemeTestTags.IMPORT_KEEP_BOTH),
                ) { Text(stringResource(R.string.theme_import_keep_both)) }
                TextButton(onClick = { onAction(ImportAction.CancelConflict) }) {
                    Text(stringResource(R.string.theme_cancel))
                }
            }
        },
    )
}

@Composable
private fun Done(
    state: ImportUiState.Done,
    onAction: (ImportAction) -> Unit,
    onClose: () -> Unit,
) {
    val text =
        when {
            state.commit.replaced -> stringResource(R.string.theme_import_replaced, state.name)
            state.commit.applied -> stringResource(R.string.theme_import_added_applied, state.name)
            else -> stringResource(R.string.theme_import_added, state.name)
        }
    Text(
        text,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    if (state.wallpaperResult == WallpaperHandoff.NO_HANDLER) {
        Note(stringResource(R.string.theme_no_wallpaper_app))
    }
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().testTag(ThemeTestTags.IMPORT_DONE)) {
        Text(stringResource(R.string.theme_import_done))
    }
    if (state.commit.replaced) {
        TextButton(onClick = { onAction(ImportAction.UndoReplace) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.theme_undo))
        }
    }
}
