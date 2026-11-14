package app.strata.launcher.feature.widgets

import android.graphics.Bitmap
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.strata.launcher.core.model.IconImage
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.WidgetProviderRef
import app.strata.launcher.core.platformapi.widgets.WidgetPreview
import app.strata.launcher.core.platformapi.widgets.WidgetProviderModel
import app.strata.launcher.core.ui.DialogText
import app.strata.launcher.core.ui.Strata
import app.strata.launcher.core.ui.TextButton
import app.strata.launcher.core.ui.focusRing

/** Loads previews and group icons for S-WPICK (the [app.strata.launcher.core.platformapi.widgets.WidgetHostController]). */
interface WidgetPreviewSource {
    suspend fun preview(
        ref: WidgetProviderRef,
        maxWidthPx: Int,
        maxHeightPx: Int,
    ): WidgetPreview

    suspend fun icon(
        ref: WidgetProviderRef,
        sizePx: Int,
    ): IconImage?
}

/**
 * S-WPICK (FR-WIDGET-02, J-10): search plus providers grouped by app, each with its preview fitted in 160 × 120 dp
 * (VISUAL_SPEC §7; generated previews on API 35+) and an accessible name "label, widget from app".
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WidgetPickerScreen(
    state: WidgetPickerState,
    onAction: (WidgetPickerAction) -> Unit,
    previews: WidgetPreviewSource,
    onBack: () -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val colors = Strata.colors
    Box(
        modifier =
            modifier
                .fillMaxSize()
                // The window background is the platform's light or dark default, not the Strata theme's.
                .background(colors.background)
                .semantics { testTagsAsResourceId = true }
                .testTag(WidgetTestTags.PICKER),
    ) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.widget_picker_back)) }
                Text(
                    text = stringResource(R.string.widget_picker_title),
                    style = Strata.typography.title,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(start = 8.dp).semantics { heading() },
                )
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = { onAction(WidgetPickerAction.Query(it)) },
                label = { Text(stringResource(R.string.widget_picker_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(WidgetTestTags.PICKER_SEARCH),
            )
            val groups = state.visibleGroups
            when {
                state.loading -> {
                    Message(stringResource(R.string.widget_picker_loading))
                }

                groups.isEmpty() -> {
                    Message(stringResource(R.string.widget_picker_empty))
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize().testTag(WidgetTestTags.PICKER_LIST)) {
                        groups.forEach { group ->
                            item(key = "g:" + group.packageName + group.appLabel) { GroupHeader(group, previews) }
                            items(
                                group.providers,
                                key = { "p:" + it.ref.component + "/" + it.ref.userSerial },
                            ) { provider ->
                                ProviderRow(provider, previews, enabled = !state.busy) {
                                    onAction(WidgetPickerAction.Pick(provider.ref))
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
    }
    if (state.askReplaceClock) {
        AlertDialog(
            onDismissRequest = { onAction(WidgetPickerAction.ReplaceClock(null)) },
            title = { Text(stringResource(R.string.widget_replace_clock_title)) },
            text = { DialogText(stringResource(R.string.widget_replace_clock_text)) },
            confirmButton = {
                TextButton(
                    onClick = { onAction(WidgetPickerAction.ReplaceClock(true)) },
                    modifier = Modifier.testTag(WidgetTestTags.CLOCK_REPLACE),
                ) { Text(stringResource(R.string.widget_replace_clock_yes)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { onAction(WidgetPickerAction.ReplaceClock(false)) },
                    modifier = Modifier.testTag(WidgetTestTags.CLOCK_KEEP),
                ) { Text(stringResource(R.string.widget_replace_clock_no)) }
            },
            modifier = Modifier.testTag(WidgetTestTags.CLOCK_DIALOG),
        )
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = Strata.typography.body,
        color = Strata.colors.textSecondary,
        modifier = Modifier.padding(24.dp),
    )
}

@Composable
private fun GroupHeader(
    group: ProviderGroup,
    previews: WidgetPreviewSource,
) {
    val sizePx = with(LocalDensity.current) { GROUP_ICON.roundToPx() }
    val first = group.providers.firstOrNull()?.ref
    val icon by produceState<IconImage?>(null, first, sizePx) { value = first?.let { previews.icon(it, sizePx) } }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                .semantics { heading() }
                .testTag(WidgetTestTags.group(group.packageName)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val bitmap = (icon?.native as? Bitmap)?.asImageBitmap()
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(GROUP_ICON))
        } else {
            Box(Modifier.size(GROUP_ICON))
        }
        Text(text = group.appLabel, style = Strata.typography.title, color = Strata.colors.textPrimary)
    }
}

@Composable
private fun ProviderRow(
    provider: WidgetProviderModel,
    previews: WidgetPreviewSource,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val base = stringResource(R.string.widget_provider_description, provider.label, provider.appLabel)
    val description =
        if (provider.profileKind ==
            ProfileKind.WORK
        ) {
            stringResource(R.string.widget_provider_work, base)
        } else {
            base
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(WidgetTestTags.provider(provider.ref.component))
                .focusRing()
                .clickable(enabled = enabled, onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Preview(provider.ref, previews)
        Text(text = provider.label, style = Strata.typography.body, color = Strata.colors.textPrimary)
    }
}

/** The provider's preview fitted in 160 × 120 dp; a generated preview (API 35+) is applied as `RemoteViews`. */
@Composable
private fun Preview(
    ref: WidgetProviderRef,
    previews: WidgetPreviewSource,
) {
    val density = LocalDensity.current
    val w = with(density) { PREVIEW_W.roundToPx() }
    val h = with(density) { PREVIEW_H.roundToPx() }
    val preview by produceState<WidgetPreview>(WidgetPreview.None, ref, w, h) { value = previews.preview(ref, w, h) }
    Box(modifier = Modifier.size(PREVIEW_W, PREVIEW_H), contentAlignment = Alignment.Center) {
        when (val p = preview) {
            is WidgetPreview.Image -> {
                val bitmap = (p.image.native as? Bitmap)?.asImageBitmap()
                if (bitmap != null) Image(bitmap = bitmap, contentDescription = null)
            }

            is WidgetPreview.Generated -> {
                val views = p.native as? RemoteViews
                if (views != null) {
                    AndroidView(
                        factory = { context ->
                            FrameLayout(context).apply {
                                // A provider's generated preview may fail to inflate: then the box stays empty.
                                runCatching { addView(views.apply(context, this)) }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            WidgetPreview.None -> {
                Unit
            }
        }
    }
}

private val PREVIEW_W = 160.dp
private val PREVIEW_H = 120.dp
private val GROUP_ICON = 24.dp
