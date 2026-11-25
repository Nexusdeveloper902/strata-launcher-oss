package app.strata.launcher.feature.theme

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.store.PresetWallpaper
import app.strata.launcher.core.theme.store.ThemeSummary
import app.strata.launcher.core.ui.DialogText
import app.strata.launcher.core.ui.DropdownMenuItem
import app.strata.launcher.core.ui.TextButton
import app.strata.launcher.core.ui.focusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.grid.items as gridItems

/** Test tags of the theme screens. */
object ThemeTestTags {
    const val LIBRARY = "theme_library"
    const val IMPORT_BUTTON = "theme_import_button"
    const val SHOW_HIDDEN = "theme_show_hidden"
    const val UNDO_BAR = "theme_undo_bar"
    const val UNDO = "theme_undo"
    const val EXPORT_DIALOG = "theme_export_dialog"
    const val EXPORT_BUNDLE = "theme_export_bundle"
    const val EXPORT_JSON = "theme_export_json"
    const val WALLPAPER_DIALOG = "theme_wallpaper_dialog"
    const val WALLPAPER_YES = "theme_wallpaper_yes"
    const val WALLPAPER_NO = "theme_wallpaper_no"
    const val WALLPAPER_PICKER = "theme_wallpaper_picker"

    fun wallpaperChoice(sha256: String) = "theme_wallpaper_choice_" + sha256.take(12)

    const val IMPORT_SCREEN = "theme_import_screen"
    const val IMPORT_ADD = "theme_import_add"
    const val IMPORT_ADD_APPLY = "theme_import_add_apply"
    const val IMPORT_CLOSE = "theme_import_close"
    const val IMPORT_ERROR = "theme_import_error"
    const val IMPORT_CONTRAST = "theme_import_contrast"
    const val IMPORT_NEWER = "theme_import_newer"
    const val IMPORT_CONFLICT = "theme_import_conflict"
    const val IMPORT_REPLACE = "theme_import_replace"
    const val IMPORT_KEEP_BOTH = "theme_import_keep_both"
    const val IMPORT_DONE = "theme_import_done"
    const val IMPORT_NOTE = "theme_import_note"
    const val NEW_THEME = "theme_new"
    const val FROM_IMAGE = "theme_from_image"

    fun draft(sessionId: String) = "theme_draft_${sessionId.lowercase()}"

    fun draftDiscard(sessionId: String) = "theme_draft_discard_${sessionId.lowercase()}"

    fun row(id: String) = "theme_row_${id.lowercase()}"

    fun menu(id: String) = "theme_menu_${id.lowercase()}"

    fun menuItem(
        id: String,
        item: String,
    ) = "theme_menu_${id.lowercase()}_$item"
}

/**
 * The theme library screen (FR-THEME-27). [onPickImport] starts the SAF picker; [onCreateDocument] starts
 * `ACTION_CREATE_DOCUMENT`, whose result the caller reports as [LibraryAction.ExportTo].
 */
@Composable
fun ThemeLibraryRoute(
    state: LibraryUiState,
    effects: Flow<LibraryEffect>,
    onAction: (LibraryAction) -> Unit,
    onPickImport: () -> Unit,
    onCreateDocument: (name: String, mime: String) -> Unit,
    onBack: () -> Unit,
    onOpenEditor: (EditorRequest) -> Unit = {},
) {
    val context = LocalContext.current
    val openEditor by rememberUpdatedState(onOpenEditor)
    val pick by rememberUpdatedState(onPickImport)
    val create by rememberUpdatedState(onCreateDocument)
    LaunchedEffect(effects) {
        effects.collect { e ->
            when (e) {
                LibraryEffect.PickImport -> {
                    pick()
                }

                is LibraryEffect.CreateDocument -> {
                    create(e.suggestedName, e.mimeType)
                }

                is LibraryEffect.Message -> {
                    Toast.makeText(context, messageText(e.message), Toast.LENGTH_SHORT).show()
                }

                is LibraryEffect.OpenEditor -> {
                    openEditor(e.request)
                }
            }
        }
    }
    BackHandler(onBack = onBack)
    ThemeLibraryScreen(state, onAction)
}

private fun messageText(m: LibraryMessage): Int =
    when (m) {
        LibraryMessage.EXPORTED -> R.string.theme_exported
        LibraryMessage.EXPORT_FAILED -> R.string.theme_export_failed
        LibraryMessage.EXPORT_TOO_LARGE -> R.string.theme_export_too_large
        LibraryMessage.UNDO_TOO_LATE -> R.string.theme_undo_too_late
        LibraryMessage.SHARE_FAILED -> R.string.theme_share_failed
        LibraryMessage.DUPLICATE_FAILED -> R.string.theme_duplicate_failed
        LibraryMessage.NO_WALLPAPER_APP -> R.string.theme_no_wallpaper_app
        LibraryMessage.WALLPAPER_FAILED -> R.string.theme_wallpaper_failed
        LibraryMessage.NOT_APPLICABLE -> R.string.theme_not_applicable
    }

@Composable
fun ThemeLibraryScreen(
    state: LibraryUiState,
    onAction: (LibraryAction) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .semantics { testTagsAsResourceId = true }
                .testTag(ThemeTestTags.LIBRARY),
    ) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.theme_library_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                if (state.hiddenCount > 0) {
                    TextButton(
                        onClick = { onAction(LibraryAction.ShowHidden(!state.showHidden)) },
                        modifier = Modifier.testTag(ThemeTestTags.SHOW_HIDDEN),
                    ) {
                        Text(
                            if (state.showHidden) {
                                stringResource(R.string.theme_hide_hidden)
                            } else {
                                pluralStringResource(R.plurals.theme_show_hidden, state.hiddenCount, state.hiddenCount)
                            },
                        )
                    }
                }
                TextButton(
                    onClick = { onAction(LibraryAction.Import) },
                    modifier = Modifier.testTag(ThemeTestTags.IMPORT_BUTTON),
                ) { Text(stringResource(R.string.theme_import)) }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = { onAction(LibraryAction.NewTheme) },
                    modifier = Modifier.testTag(ThemeTestTags.NEW_THEME),
                ) { Text(stringResource(R.string.theme_new)) }
                TextButton(
                    onClick = { onAction(LibraryAction.FromImage) },
                    modifier = Modifier.testTag(ThemeTestTags.FROM_IMAGE),
                ) { Text(stringResource(R.string.theme_from_image)) }
            }
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.drafts, key = { "draft_" + it.sessionId }) { d -> DraftRowView(d, onAction) }
                items(state.rows, key = { it.id.lowercase() }) { t ->
                    ThemeRow(t, active = t.id.equals(state.activeId, ignoreCase = true), onAction)
                }
            }
            state.pendingDelete?.let { ticket ->
                UndoBar(ticket.name) { onAction(LibraryAction.UndoDelete) }
            }
        }
        state.exportChoiceFor?.let { ExportChoice(it, onAction) }
        if (state.wallpaperPromptFor != null) {
            if (state.wallpaperChoices.size > 1) {
                WallpaperPicker(
                    choices = state.wallpaperChoices,
                    thumbs = state.wallpaperThumbs,
                    onPick = { onAction(LibraryAction.WallpaperPick(it)) },
                    onDismiss = { onAction(LibraryAction.Wallpaper(false)) },
                )
            } else {
                WallpaperPrompt { accept -> onAction(LibraryAction.Wallpaper(accept)) }
            }
        }
    }
}

@Composable
private fun ThemeRow(
    t: ThemeSummary,
    active: Boolean,
    onAction: (LibraryAction) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val badges =
        listOfNotNull(
            stringResource(R.string.theme_active).takeIf { active },
            stringResource(R.string.theme_built_in).takeIf { t.builtIn },
            stringResource(R.string.theme_hidden).takeIf { t.hidden },
            stringResource(R.string.theme_needs_update).takeIf { t.needsAppUpdate },
            stringResource(R.string.theme_caused_problem).takeIf { t.causedProblem },
        )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .focusRing()
                .clickable(enabled = t.applicable) { onAction(LibraryAction.Apply(t.id)) }
                .padding(start = 16.dp)
                .testTag(ThemeTestTags.row(t.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                t.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (badges.isNotEmpty()) {
                Text(
                    badges.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            val more = stringResource(R.string.theme_more, t.name)
            TextButton(
                onClick = { menu = true },
                modifier =
                    Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = more }
                        .testTag(ThemeTestTags.menu(t.id)),
            ) { Text("⋮") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                fun item(
                    key: String,
                    label: Int,
                    action: LibraryAction,
                ): @Composable () -> Unit =
                    {
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            onClick = {
                                menu = false
                                onAction(action)
                            },
                            modifier = Modifier.testTag(ThemeTestTags.menuItem(t.id, key)),
                        )
                    }
                if (t.applicable) item("apply", R.string.theme_apply, LibraryAction.Apply(t.id))()
                if (t.hasWallpapers) {
                    item(
                        "wallpaper",
                        R.string.theme_set_wallpaper,
                        LibraryAction.ChooseWallpaper(t.id),
                    )()
                }
                if (!t.needsAppUpdate) {
                    item("edit", R.string.theme_edit, LibraryAction.Edit(t.id))()
                    item("duplicate", R.string.theme_duplicate, LibraryAction.Duplicate(t.id))()
                    item("export", R.string.theme_export, LibraryAction.Export(t.id))()
                    item("share", R.string.theme_share, LibraryAction.Share(t.id))()
                }
                when {
                    !t.builtIn -> {
                        item("delete", R.string.theme_delete, LibraryAction.Delete(t.id))()
                    }

                    t.id.equals(StrataDefault.ID, ignoreCase = true) -> {
                        Unit // Strata Default is the recovery target: always listed.
                    }

                    t.hidden -> {
                        item("unhide", R.string.theme_unhide, LibraryAction.SetHidden(t.id, false))()
                    }

                    else -> {
                        item("hide", R.string.theme_hide, LibraryAction.SetHidden(t.id, true))()
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftRowView(
    d: DraftRow,
    onAction: (LibraryAction) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .focusRing()
                .clickable { onAction(LibraryAction.ContinueDraft(d.sessionId)) }
                .padding(start = 16.dp)
                .testTag(ThemeTestTags.draft(d.sessionId)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.theme_draft_row, d.name),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                stringResource(R.string.theme_draft_continue),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = { onAction(LibraryAction.DiscardDraft(d.sessionId)) },
            modifier = Modifier.heightIn(min = 48.dp).testTag(ThemeTestTags.draftDiscard(d.sessionId)),
        ) { Text(stringResource(R.string.editor_draft_discard)) }
    }
}

@Composable
private fun UndoBar(
    name: String,
    onUndo: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        modifier = Modifier.fillMaxWidth().padding(8.dp).testTag(ThemeTestTags.UNDO_BAR),
    ) {
        Row(
            modifier =
                Modifier
                    .padding(start = 16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.theme_deleted, name),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUndo, modifier = Modifier.testTag(ThemeTestTags.UNDO)) {
                Text(stringResource(R.string.theme_undo), color = MaterialTheme.colorScheme.inversePrimary)
            }
        }
    }
}

@Composable
private fun ExportChoice(
    t: ThemeSummary,
    onAction: (LibraryAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(LibraryAction.DismissExportChoice) },
        modifier = Modifier.testTag(ThemeTestTags.EXPORT_DIALOG),
        title = { Text(stringResource(R.string.theme_export_title, t.name)) },
        text = { DialogText(stringResource(R.string.theme_export_font_licence)) },
        confirmButton = {
            TextButton(
                onClick = { onAction(LibraryAction.ExportAs(t.id, bundle = true)) },
                modifier = Modifier.testTag(ThemeTestTags.EXPORT_BUNDLE),
            ) { Text(stringResource(R.string.theme_export_bundle)) }
        },
        dismissButton = {
            TextButton(
                onClick = { onAction(LibraryAction.ExportAs(t.id, bundle = false)) },
                modifier = Modifier.testTag(ThemeTestTags.EXPORT_JSON),
            ) { Text(stringResource(R.string.theme_export_json)) }
        },
    )
}

/** "Also set this theme's wallpaper?" (THEME_SYSTEM §4.3): declining starts nothing. */
@Composable
fun WallpaperPrompt(onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        modifier = Modifier.testTag(ThemeTestTags.WALLPAPER_DIALOG),
        title = { Text(stringResource(R.string.theme_wallpaper_question)) },
        text = { DialogText(stringResource(R.string.theme_wallpaper_explain)) },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }, modifier = Modifier.testTag(ThemeTestTags.WALLPAPER_YES)) {
                Text(stringResource(R.string.theme_wallpaper_yes))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }, modifier = Modifier.testTag(ThemeTestTags.WALLPAPER_NO)) {
                Text(stringResource(R.string.theme_wallpaper_no))
            }
        },
    )
}

/**
 * The wallpaper prompt for a theme with a wallpaper set (FR-THEME-36): thumbnails with their labels; picking one hands
 * it to the system wallpaper activity like the single-image prompt (§4.3), "Not now" starts nothing.
 */
@Composable
fun WallpaperPicker(
    choices: List<PresetWallpaper>,
    thumbs: Map<String, ByteArray>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ThemeTestTags.WALLPAPER_PICKER),
        title = { Text(stringResource(R.string.theme_wallpaper_question)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogText(stringResource(R.string.theme_wallpaper_pick_explain))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 88.dp),
                    modifier = Modifier.heightIn(max = 440.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    gridItems(choices, key = { it.sha256 }) { c ->
                        Column(
                            modifier =
                                Modifier
                                    .focusRing()
                                    .clickable(onClickLabel = c.label) { onPick(c.sha256) }
                                    .semantics(mergeDescendants = true) { role = Role.Button }
                                    .testTag(ThemeTestTags.wallpaperChoice(c.sha256)),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            WallpaperThumb(thumbs[c.sha256])
                            Text(
                                c.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(ThemeTestTags.WALLPAPER_NO)) {
                Text(stringResource(R.string.theme_wallpaper_not_now))
            }
        },
    )
}

/** A phone-shaped thumbnail of [bytes], decoded downsampled off the main thread; a plain plate until it is ready. */
@Composable
private fun WallpaperThumb(bytes: ByteArray?) {
    val image by produceState<ImageBitmap?>(null, bytes) {
        value =
            bytes?.let {
                withContext(Dispatchers.Default) {
                    val options = BitmapFactory.Options().apply { inSampleSize = THUMB_SAMPLE }
                    runCatching { BitmapFactory.decodeByteArray(it, 0, it.size, options)?.asImageBitmap() }.getOrNull()
                }
            }
    }
    val shape = RoundedCornerShape(8.dp)
    val frame =
        Modifier
            .fillMaxWidth()
            .aspectRatio(THUMB_ASPECT)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
    val bitmap = image
    if (bitmap == null) {
        Box(modifier = frame)
    } else {
        Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = frame)
    }
}

/** A portrait phone screen (the reference device is 1080 × 2340). */
private const val THUMB_ASPECT = 1080f / 2340f
private const val THUMB_SAMPLE = 8
