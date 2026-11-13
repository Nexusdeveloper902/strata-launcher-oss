package app.strata.launcher.feature.theme

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.strata.launcher.core.json.JsonBool
import app.strata.launcher.core.json.JsonNumber
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.json.JsonValue
import app.strata.launcher.core.theme.color.Hct
import app.strata.launcher.core.theme.color.Rgb
import app.strata.launcher.core.theme.contrast.ContrastPair
import app.strata.launcher.core.theme.editor.ContrastReport
import app.strata.launcher.core.theme.editor.EditorField
import app.strata.launcher.core.theme.editor.EditorFields
import app.strata.launcher.core.theme.editor.EditorGroup
import app.strata.launcher.core.theme.editor.FieldKind
import app.strata.launcher.core.theme.editor.PairCheck
import app.strata.launcher.core.theme.model.Variant
import app.strata.launcher.core.theme.parse.ThemeError
import app.strata.launcher.core.ui.Checkbox
import app.strata.launcher.core.ui.FilterChip
import app.strata.launcher.core.ui.Slider
import app.strata.launcher.core.ui.Switch
import app.strata.launcher.core.ui.TextButton
import app.strata.launcher.core.ui.focusRing
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import kotlin.math.roundToInt

/** Test tags of the editor (also exposed as resource ids for the Macrobenchmarks). */
object EditorTestTags {
    const val SCREEN = "editor_screen"
    const val SCROLL = "editor_scroll"
    const val PREVIEW = "editor_preview"
    const val PROBLEM_MARK = "editor_problem_mark"
    const val UNDO = "editor_undo"
    const val REDO = "editor_redo"
    const val SAVE = "editor_save"
    const val APPLY = "editor_apply"
    const val JSON = "editor_json"
    const val BANNER = "editor_banner"
    const val BANNER_STATUS = "editor_banner_status"
    const val FIX = "editor_fix"
    const val SHOW_ME = "editor_show_me"
    const val PREVIEW_DARK = "editor_preview_dark"
    const val PREVIEW_WALLPAPER = "editor_preview_wallpaper"
    const val PREVIEW_PROBLEMS = "editor_preview_problems"
    const val PREVIEW_SAMPLE = "editor_preview_sample"
    const val DRAFT_OFFER = "editor_draft_offer"
    const val DRAFT_RESTORE = "editor_draft_restore"
    const val DRAFT_DISCARD = "editor_draft_discard"
    const val LEAVE_DIALOG = "editor_leave_dialog"
    const val LEAVE_SAVE = "editor_leave_save"
    const val LEAVE_DISCARD = "editor_leave_discard"
    const val LEAVE_KEEP = "editor_leave_keep"
    const val JSON_VIEW = "editor_json_view"
    const val JSON_TEXT = "editor_json_text"
    const val JSON_APPLY = "editor_json_apply"
    const val JSON_COPY = "editor_json_copy"
    const val JSON_CLOSE = "editor_json_close"
    const val JSON_ERROR = "editor_json_error"
    const val FROM_IMAGE = "editor_from_image"
    const val KEEP_IMAGE = "editor_keep_image"
    const val RESET_PRESET = "editor_reset_preset"
    const val RESET_DEFAULT = "editor_reset_default"

    fun group(key: String) = "editor_group_$key"

    fun resetGroup(key: String) = "editor_reset_group_$key"

    fun field(id: String) = "editor_field_${id.replace('.', '_')}"

    fun reset(id: String) = "editor_reset_${id.replace('.', '_')}"

    fun swatch(id: String) = "editor_swatch_${id.replace('.', '_')}"

    fun slider(id: String) = "editor_slider_${id.replace('.', '_')}"

    fun option(
        id: String,
        wire: String,
    ) = "editor_option_${id.replace('.', '_')}_$wire"

    fun chip(
        variant: Variant,
        role: String,
    ) = "editor_chip_${variant.wire}_$role"

    fun pairLine(
        pair: ContrastPair,
        variant: Variant,
    ) = "editor_pair_${pair.name.lowercase()}_${variant.wire}"
}

/** The editor screen with its one-shot effects. [onPickImage] starts the system photo picker. */
@Composable
fun ThemeEditorRoute(
    state: EditorUiState,
    effects: Flow<EditorEffect>,
    onAction: (EditorAction) -> Unit,
    onPickImage: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val pick by rememberUpdatedState(onPickImage)
    val scroll = rememberScrollState()
    LaunchedEffect(effects) {
        effects.collect { e ->
            when (e) {
                EditorEffect.PickImage -> pick()
                EditorEffect.ScrollToPreview -> scroll.animateScrollTo(0)
                is EditorEffect.Message -> Toast.makeText(context, messageText(e.message), Toast.LENGTH_SHORT).show()
            }
        }
    }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(state.done) { if (state.done) done() }
    BackHandler(enabled = state.json == null) { onAction(EditorAction.Back) }
    BackHandler(enabled = state.json != null) { onAction(EditorAction.CloseJson) }
    ThemeEditorScreen(state, onAction, scroll)
}

private fun messageText(m: EditorMessage): Int =
    when (m) {
        EditorMessage.SAVED -> R.string.editor_saved
        EditorMessage.APPLIED -> R.string.editor_applied
        EditorMessage.SAVE_FAILED -> R.string.editor_save_failed
        EditorMessage.NOT_ENOUGH_STORAGE -> R.string.theme_error_storage
        EditorMessage.JSON_APPLIED -> R.string.editor_json_applied
        EditorMessage.JSON_ID_KEPT -> R.string.editor_json_id_kept
        EditorMessage.IMAGE_UNSUPPORTED -> R.string.editor_image_unsupported
        EditorMessage.IMAGE_UNREADABLE -> R.string.editor_image_unreadable
        EditorMessage.IMAGE_TOO_LARGE -> R.string.editor_image_too_large
        EditorMessage.IMAGE_APPLIED -> R.string.editor_image_applied
        EditorMessage.IMAGE_NOT_KEPT -> R.string.editor_image_not_kept
        EditorMessage.NOTHING_TO_FIX -> R.string.editor_nothing_to_fix
        EditorMessage.FIXED -> R.string.editor_fixed
    }

@Composable
fun ThemeEditorScreen(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
    scroll: androidx.compose.foundation.ScrollState = rememberScrollState(),
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .semantics { testTagsAsResourceId = true }
                .testTag(EditorTestTags.SCREEN),
    ) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(state, onAction)
            val doc = state.document
            if (doc == null || state.preview == null) return@Column
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth >= WIDE_LAYOUT
                if (wide) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp)) {
                            PreviewBlock(state, onAction)
                        }
                        Column(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .verticalScroll(scroll)
                                    .padding(8.dp)
                                    .testTag(EditorTestTags.SCROLL),
                        ) {
                            Controls(state, onAction)
                        }
                    }
                } else {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(scroll)
                                .padding(8.dp)
                                .testTag(EditorTestTags.SCROLL),
                    ) {
                        PreviewBlock(state, onAction)
                        Controls(state, onAction)
                    }
                }
            }
        }
        state.json?.let { JsonView(it, onAction) }
        if (state.leavePrompt) LeavePrompt(onAction)
    }
}

@Composable
private fun TopBar(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val back = stringResource(R.string.editor_back)
        TextButton(
            onClick = { onAction(EditorAction.Back) },
            modifier = Modifier.semantics { contentDescription = back },
        ) { Text("←") }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                state.document?.name ?: stringResource(R.string.editor_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            if (state.dirty) {
                Text(
                    stringResource(R.string.editor_unsaved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(
            onClick = { onAction(EditorAction.Undo) },
            enabled = state.canUndo,
            modifier = Modifier.testTag(EditorTestTags.UNDO),
        ) { Text(stringResource(R.string.editor_undo)) }
        TextButton(
            onClick = { onAction(EditorAction.Redo) },
            enabled = state.canRedo,
            modifier = Modifier.testTag(EditorTestTags.REDO),
        ) { Text(stringResource(R.string.editor_redo)) }
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = { onAction(EditorAction.OpenJson) }, modifier = Modifier.testTag(EditorTestTags.JSON)) {
            Text(stringResource(R.string.editor_json))
        }
        TextButton(
            onClick = { onAction(EditorAction.Save) },
            enabled = state.document != null && (state.dirty || state.isNew),
            modifier = Modifier.testTag(EditorTestTags.SAVE),
        ) { Text(stringResource(R.string.editor_save)) }
        TextButton(
            onClick = { onAction(EditorAction.Apply) },
            enabled = state.document != null,
            modifier = Modifier.testTag(EditorTestTags.APPLY),
        ) { Text(stringResource(R.string.editor_apply)) }
    }
}

@Composable
private fun PreviewBlock(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
) {
    state.draftOffer?.let { DraftOfferCard(onAction) }
    state.contrast?.let { ContrastBanner(it, onAction) }
    val preview = state.preview ?: return
    val theme = if (state.previewDark) preview.dark else preview.light
    val problems =
        if (state.showProblems) {
            state.contrast
                ?.failing
                ?.filter { it.variant == theme.variant }
                ?.mapTo(HashSet()) { it.pair }
                .orEmpty()
        } else {
            emptySet()
        }
    val apps = if (state.sampleApps || state.appLabels.isEmpty()) SAMPLE_APPS else state.appLabels
    ThemePreview(theme, apps, state.swatches, state.previewWallpaper, problems)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Toggle(
            stringResource(R.string.editor_preview_dark),
            state.previewDark,
            EditorTestTags.PREVIEW_DARK,
        ) { onAction(EditorAction.PreviewDark(it)) }
        Toggle(
            stringResource(R.string.editor_preview_wallpaper),
            state.previewWallpaper,
            EditorTestTags.PREVIEW_WALLPAPER,
        ) { onAction(EditorAction.PreviewWallpaper(it)) }
        Toggle(
            stringResource(R.string.editor_preview_problems),
            state.showProblems,
            EditorTestTags.PREVIEW_PROBLEMS,
        ) { onAction(EditorAction.ShowProblems(it)) }
        Toggle(
            stringResource(R.string.editor_preview_sample),
            state.sampleApps,
            EditorTestTags.PREVIEW_SAMPLE,
        ) { onAction(EditorAction.SampleApps(it)) }
    }
}

@Composable
private fun Toggle(
    label: String,
    on: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    FilterChip(selected = on, onClick = { onChange(!on) }, label = { Text(label) }, modifier = Modifier.testTag(tag))
}

/** The recovery offer is modal (§13): until Restore or Discard is chosen nothing edits the session or the draft. */
@Composable
private fun DraftOfferCard(onAction: (EditorAction) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        modifier = Modifier.testTag(EditorTestTags.DRAFT_OFFER),
        text = { Text(stringResource(R.string.editor_draft_offer)) },
        confirmButton = {
            TextButton(
                onClick = { onAction(EditorAction.RestoreDraft) },
                modifier = Modifier.testTag(EditorTestTags.DRAFT_RESTORE),
            ) { Text(stringResource(R.string.editor_draft_restore)) }
        },
        dismissButton = {
            TextButton(
                onClick = { onAction(EditorAction.DiscardDraft) },
                modifier = Modifier.testTag(EditorTestTags.DRAFT_DISCARD),
            ) { Text(stringResource(R.string.editor_draft_discard)) }
        },
    )
}

@Composable
private fun pairText(c: PairCheck): String {
    val line =
        stringResource(
            when (c.pair) {
                ContrastPair.P1 -> R.string.editor_pair_p1
                ContrastPair.P2 -> R.string.editor_pair_p2
                ContrastPair.P3 -> R.string.editor_pair_p3
                ContrastPair.P4 -> R.string.editor_pair_p4
                ContrastPair.P5 -> R.string.editor_pair_p5
                ContrastPair.P6 -> R.string.editor_pair_p6
                ContrastPair.P7 -> R.string.editor_pair_p7
                ContrastPair.P8 -> R.string.editor_pair_p8
            },
        )
    return stringResource(R.string.editor_pair_variant, line, variantText(c.variant))
}

@Composable
private fun variantText(v: Variant): String =
    stringResource(if (v == Variant.LIGHT) R.string.editor_light else R.string.editor_dark)

/**
 * The §7.6 banner: failing pairs in plain words with "Fix" and "Show me". Its status line is a polite live region
 * whose text changes only when the set of problems changes, so TalkBack announces it once, not on every slider tick.
 */
@Composable
private fun ContrastBanner(
    report: ContrastReport,
    onAction: (EditorAction) -> Unit,
) {
    val failing = report.failing
    Surface(
        color = if (failing.isEmpty()) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(EditorTestTags.BANNER),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val scheme = MaterialTheme.colorScheme
            val onColour = if (failing.isEmpty()) scheme.onSurface else scheme.onErrorContainer
            Text(
                stringResource(if (failing.isEmpty()) R.string.editor_banner_ok else R.string.editor_banner_title),
                color = onColour,
                style = MaterialTheme.typography.titleSmall,
                modifier =
                    Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag(EditorTestTags.BANNER_STATUS),
            )
            if (failing.isEmpty()) return@Column
            for (c in failing) {
                Text(
                    stringResource(R.string.editor_bullet, pairText(c)),
                    color = onColour,
                    modifier = Modifier.testTag(EditorTestTags.pairLine(c.pair, c.variant)),
                )
            }
            Text(
                stringResource(R.string.editor_enforce_note),
                color = onColour,
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                TextButton(onClick = { onAction(EditorAction.Fix) }, modifier = Modifier.testTag(EditorTestTags.FIX)) {
                    Text(stringResource(R.string.editor_fix))
                }
                TextButton(
                    onClick = { onAction(EditorAction.ShowMe) },
                    modifier = Modifier.testTag(EditorTestTags.SHOW_ME),
                ) { Text(stringResource(R.string.editor_show_me)) }
            }
        }
    }
}

@Composable
private fun Controls(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
) {
    for (group in EditorFields.GROUPS) {
        GroupSection(group, state, onAction)
    }
    ThemeResets(state, onAction)
}

@Composable
private fun groupLabel(key: String): String = EditorLabels.group(key)?.let { stringResource(it) } ?: key

@Composable
private fun GroupSection(
    group: EditorGroup,
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
) {
    val expanded = state.expandedGroup == group.key
    val label = groupLabel(group.key)
    val stateText = stringResource(if (expanded) R.string.editor_expanded else R.string.editor_collapsed)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .focusRing()
                .clickable { onAction(EditorAction.Expand(if (expanded) null else group.key)) }
                .semantics {
                    heading()
                    stateDescription = stateText
                }.testTag(EditorTestTags.group(group.key)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(if (expanded) R.string.editor_group_open else R.string.editor_group_closed, label),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        if (expanded) {
            val desc = stringResource(R.string.editor_reset_group, label)
            TextButton(
                onClick = { onAction(EditorAction.ResetGroup(group.key)) },
                modifier =
                    Modifier.semantics { contentDescription = desc }.testTag(
                        EditorTestTags.resetGroup(group.key),
                    ),
            ) { Text(stringResource(R.string.editor_reset)) }
        }
    }
    if (!expanded) return
    if (group.key == "colors") ImageSection(state, onAction)
    for (field in group.fields) FieldRow(field, state, onAction)
}

@Composable
private fun fieldLabel(field: EditorField): String {
    if (field.role != null && field.variant != null) {
        val role = EditorLabels.role(field.role!!.wire)?.let { stringResource(it) } ?: field.role!!.wire
        return stringResource(R.string.editor_colour_label, role, variantText(field.variant!!))
    }
    return EditorLabels.field(field.id)?.let { stringResource(it) } ?: field.id
}

@Composable
private fun FieldRow(
    field: EditorField,
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
) {
    val doc = state.document ?: return
    val effective = state.effective ?: return
    val value = EditorFields.displayValue(field, doc, effective)
    val label = fieldLabel(field)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 8.dp,
                    vertical = 4.dp,
                ).testTag(EditorTestTags.field(field.id)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            if (field.kind == FieldKind.Toggle) {
                val on = (value as? JsonBool)?.value == true
                Switch(
                    checked = on,
                    onCheckedChange = { onAction(EditorAction.Set(field.id, it)) },
                    modifier = Modifier.semantics { contentDescription = label },
                )
            }
            if (field.resettable) {
                val desc = stringResource(R.string.editor_reset_field, label)
                TextButton(
                    onClick = { onAction(EditorAction.Reset(field.id)) },
                    modifier = Modifier.semantics { contentDescription = desc }.testTag(EditorTestTags.reset(field.id)),
                ) { Text(stringResource(R.string.editor_reset)) }
            }
        }
        when (val k = field.kind) {
            FieldKind.Toggle -> {}

            is FieldKind.Choice -> {
                ChoiceControl(field, k, (value as? JsonString)?.value, onAction)
            }

            is FieldKind.Whole -> {
                NumberControl(
                    field,
                    label,
                    k.min.toDouble(),
                    k.max.toDouble(),
                    k.step.toDouble(),
                    value,
                    onAction,
                )
            }

            is FieldKind.Decimal -> {
                NumberControl(field, label, k.min, k.max, k.step, value, onAction)
            }

            FieldKind.Colour -> {
                ColourControl(field, label, (value as? JsonString)?.value, state, onAction)
            }

            is FieldKind.Text -> {
                TextControl(field, label, k, (value as? JsonString)?.value.orEmpty(), onAction)
            }
        }
    }
}

@Composable
private fun ChoiceControl(
    field: EditorField,
    kind: FieldKind.Choice,
    current: String?,
    onAction: (EditorAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (o in kind.options) {
            FilterChip(
                selected = o == current,
                onClick = { onAction(EditorAction.Set(field.id, o)) },
                label = { Text(EditorLabels.option(o)?.let { stringResource(it) } ?: o) },
                modifier = Modifier.testTag(EditorTestTags.option(field.id, o)),
            )
        }
    }
}

private fun JsonValue?.asDouble(): Double? = (this as? JsonNumber)?.toDouble()

@Composable
private fun NumberControl(
    field: EditorField,
    label: String,
    min: Double,
    max: Double,
    step: Double,
    value: JsonValue?,
    onAction: (EditorAction) -> Unit,
) {
    val v = value.asDouble() ?: min
    val count = ((max - min) / step).roundToInt()
    val shown = if (field.kind is FieldKind.Whole) v.roundToInt().toString() else String.format(Locale.ROOT, "%.2f", v)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = v.toFloat(),
            onValueChange = { onAction(EditorAction.Set(field.id, it.toDouble(), dragging = true)) },
            onValueChangeFinished = { onAction(EditorAction.EndDrag) },
            valueRange = min.toFloat()..max.toFloat(),
            // Discrete steps for keyboard / D-pad (each press moves one step); continuous when there are many.
            steps = if (count in 2..MAX_SLIDER_STEPS) count - 1 else 0,
            modifier =
                Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = label
                        stateDescription = shown
                    }.testTag(EditorTestTags.slider(field.id)),
        )
        Text(
            shown,
            modifier = Modifier.width(56.dp).padding(start = 8.dp),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun TextControl(
    field: EditorField,
    label: String,
    kind: FieldKind.Text,
    value: String,
    onAction: (EditorAction) -> Unit,
) {
    var text by remember(field.id) { mutableStateOf(value) }
    var focused by remember(field.id) { mutableStateOf(false) }
    if (!focused && text != value) text = value
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.take(kind.maxLength)
            if (kind.pattern == null || kind.pattern!!.matches(text)) {
                onAction(EditorAction.Set(field.id, text, dragging = true))
            }
        },
        label = { Text(label) },
        singleLine = !kind.multiline,
        isError = kind.pattern != null && !kind.pattern!!.matches(text),
        keyboardOptions = KeyboardOptions(imeAction = if (kind.multiline) ImeAction.Default else ImeAction.Done),
        modifier =
            Modifier.fillMaxWidth().onFocusChanged {
                if (focused && !it.isFocused) onAction(EditorAction.EndDrag)
                focused = it.isFocused
            },
    )
}

@Composable
private fun ColourControl(
    field: EditorField,
    label: String,
    hex: String?,
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
) {
    val argb = hex?.let { Rgb.parseHex(it) } ?: return
    val chip =
        if (field.role != null &&
            field.variant != null
        ) {
            state.contrast?.chip(field.variant!!, field.role!!)
        } else {
            null
        }
    var open by remember(field.id) { mutableStateOf(false) }
    val swatchDescription = stringResource(R.string.editor_colour_value, label, Rgb.toHex(argb))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .background(Color(argb), RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .focusRing()
                    .clickable { open = !open }
                    .testTag(EditorTestTags.swatch(field.id))
                    .semantics { contentDescription = swatchDescription },
        )
        Text(
            Rgb.toHex(argb),
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        if (chip != null && field.role != null) ContrastChip(chip, field.variant!!, field.role!!.wire)
    }
    if (!open) return
    var text by remember(field.id, argb) { mutableStateOf(Rgb.toHex(argb)) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.take(HEX_LENGTH)
            Rgb.parseHex(text.trim())?.let { c -> onAction(EditorAction.Set(field.id, c)) }
        },
        label = { Text(stringResource(R.string.editor_hex)) },
        singleLine = true,
        isError = Rgb.parseHex(text.trim()) == null,
        modifier = Modifier.fillMaxWidth(),
    )
    val hct = Hct.fromArgb(argb)
    HctSlider(field, "hue", stringResource(R.string.editor_hue), hct.hue, 0.0..360.0, onAction) {
        Hct.from(it, hct.chroma, hct.tone).toArgb()
    }
    HctSlider(field, "chroma", stringResource(R.string.editor_chroma), hct.chroma, 0.0..MAX_CHROMA, onAction) {
        Hct.from(hct.hue, it, hct.tone).toArgb()
    }
    HctSlider(field, "tone", stringResource(R.string.editor_tone), hct.tone, 0.0..100.0, onAction) {
        Hct.from(hct.hue, hct.chroma, it).toArgb()
    }
    if (state.swatches.isNotEmpty()) {
        Text(stringResource(R.string.editor_swatches), style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (s in state.swatches) {
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .background(Color(s), RoundedCornerShape(24.dp))
                            .focusRing()
                            .clickable { onAction(EditorAction.Set(field.id, s)) }
                            .semantics { contentDescription = Rgb.toHex(s) },
                )
            }
        }
    }
}

@Composable
private fun HctSlider(
    field: EditorField,
    axis: String,
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    onAction: (EditorAction) -> Unit,
    toArgb: (Double) -> Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall)
        val shown = value.roundToInt().toString()
        Slider(
            value = value.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
            onValueChange = { onAction(EditorAction.Set(field.id, toArgb(it.toDouble()), dragging = true)) },
            onValueChangeFinished = { onAction(EditorAction.EndDrag) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            modifier =
                Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = label
                        stateDescription = shown
                    }.testTag(EditorTestTags.slider("${field.id}.$axis")),
        )
    }
}

/** "4.8 : 1 AA" / "2.9 : 1, hard to read" with a ✓/✗ icon: pass and fail are told by text, not colour (§7.6). */
@Composable
private fun ContrastChip(
    c: PairCheck,
    variant: Variant,
    role: String,
) {
    val ratio = String.format(LocalConfiguration.current.locales[0], "%.1f", c.ratio)
    val text = stringResource(if (c.passes) R.string.editor_chip_pass else R.string.editor_chip_fail, ratio)
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (c.passes) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (c.passes) scheme.onSurfaceVariant else scheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag(EditorTestTags.chip(variant, role)),
        )
    }
}

@Composable
private fun ImageSection(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
        TextButton(
            onClick = { onAction(EditorAction.PickImage) },
            enabled = !state.busy,
            modifier = Modifier.testTag(EditorTestTags.FROM_IMAGE),
        ) { Text(stringResource(R.string.editor_from_image)) }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .padding(horizontal = 8.dp)
                .heightIn(min = 48.dp)
                .focusRing()
                // One focus stop with the label as its name; the box only shows the state (NFR-A11Y-01).
                .toggleable(
                    value = state.keepImageAsWallpaper,
                    role = Role.Checkbox,
                    onValueChange = { onAction(EditorAction.KeepImageAsWallpaper(it)) },
                ).testTag(EditorTestTags.KEEP_IMAGE),
    ) {
        Checkbox(checked = state.keepImageAsWallpaper, onCheckedChange = null)
        Text(stringResource(R.string.editor_keep_image), color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun ThemeResets(
    state: EditorUiState,
    onAction: (EditorAction) -> Unit,
) {
    Text(
        stringResource(R.string.editor_reset_theme_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(8.dp).semantics { heading() },
    )
    state.basedOnName?.let { name ->
        TextButton(
            onClick = { onAction(EditorAction.ResetTheme(toPreset = true)) },
            modifier = Modifier.testTag(EditorTestTags.RESET_PRESET),
        ) { Text(stringResource(R.string.editor_reset_preset, name)) }
    }
    TextButton(
        onClick = { onAction(EditorAction.ResetTheme(toPreset = false)) },
        modifier = Modifier.testTag(EditorTestTags.RESET_DEFAULT),
    ) { Text(stringResource(R.string.editor_reset_default)) }
}

@Composable
private fun JsonView(
    json: JsonViewState,
    onAction: (EditorAction) -> Unit,
) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize().testTag(EditorTestTags.JSON_VIEW),
    ) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
            Text(stringResource(R.string.editor_json_title), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onAction(EditorAction.ApplyJson) },
                    enabled = json.error == null,
                    modifier = Modifier.testTag(EditorTestTags.JSON_APPLY),
                ) { Text(stringResource(R.string.editor_json_apply)) }
                val copied = stringResource(R.string.editor_json_copied)
                TextButton(
                    onClick = { copy(context, json.text, copied) },
                    modifier = Modifier.testTag(EditorTestTags.JSON_COPY),
                ) { Text(stringResource(R.string.editor_json_copy)) }
                TextButton(
                    onClick = { onAction(EditorAction.CloseJson) },
                    modifier = Modifier.testTag(EditorTestTags.JSON_CLOSE),
                ) { Text(stringResource(R.string.editor_json_close)) }
            }
            val error = json.error
            // Only the valid ↔ invalid change is announced (politely), never each keystroke's details.
            Text(
                stringResource(if (error == null) R.string.editor_json_ok else R.string.editor_json_invalid),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Column(modifier = Modifier.testTag(EditorTestTags.JSON_ERROR)) {
                when {
                    error != null -> {
                        Text(jsonErrorText(error), color = MaterialTheme.colorScheme.error)
                    }

                    json.issues.isEmpty() -> {
                        Text(stringResource(R.string.editor_json_valid))
                    }

                    else -> {
                        for (i in json.issues.take(MAX_ISSUES)) {
                            Text(stringResource(R.string.editor_json_issue, i.path.ifEmpty { "/" }, i.message))
                        }
                    }
                }
            }
            OutlinedTextField(
                value = json.text,
                onValueChange = { onAction(EditorAction.JsonChanged(it)) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().weight(1f).testTag(EditorTestTags.JSON_TEXT),
            )
        }
    }
}

@Composable
private fun jsonErrorText(e: ThemeError): String {
    val line = e.issue.line
    val col = e.issue.column
    return when (e) {
        is ThemeError.Damaged -> {
            if (line != null && col != null) {
                stringResource(R.string.theme_error_damaged_at, line, col)
            } else {
                stringResource(R.string.theme_error_damaged)
            }
        }

        is ThemeError.TooLarge -> {
            stringResource(R.string.theme_error_too_large)
        }

        is ThemeError.NotATheme -> {
            stringResource(R.string.theme_error_not_a_theme)
        }

        is ThemeError.NewerVersion -> {
            stringResource(R.string.theme_error_newer)
        }
    }
}

private fun copy(
    context: Context,
    text: String,
    confirmation: String,
) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("theme.json", text))
    Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
}

@Composable
private fun LeavePrompt(onAction: (EditorAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(EditorAction.KeepEditing) },
        modifier = Modifier.testTag(EditorTestTags.LEAVE_DIALOG),
        title = { Text(stringResource(R.string.editor_leave_title)) },
        confirmButton = {
            TextButton(
                onClick = { onAction(EditorAction.LeaveSave) },
                modifier = Modifier.testTag(EditorTestTags.LEAVE_SAVE),
            ) {
                Text(stringResource(R.string.editor_leave_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = { onAction(EditorAction.LeaveDiscard) },
                    modifier = Modifier.testTag(EditorTestTags.LEAVE_DISCARD),
                ) { Text(stringResource(R.string.editor_leave_discard)) }
                TextButton(
                    onClick = { onAction(EditorAction.KeepEditing) },
                    modifier = Modifier.testTag(EditorTestTags.LEAVE_KEEP),
                ) { Text(stringResource(R.string.editor_leave_keep)) }
            }
        },
    )
}

private val WIDE_LAYOUT = 720.dp
private const val MAX_SLIDER_STEPS = 60
private const val HEX_LENGTH = 7
private const val MAX_CHROMA = 120.0
private const val MAX_ISSUES = 20
