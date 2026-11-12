package app.strata.launcher.feature.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.strata.launcher.core.model.WidgetHeights
import app.strata.launcher.core.ui.SheetItem
import app.strata.launcher.core.ui.Strata
import app.strata.launcher.core.ui.StrataSheet

object WidgetTestTags {
    const val AREA = "widget_area"
    const val HOST = "widget_host"
    const val PLACEHOLDER = "widget_placeholder"
    const val READD = "widget_readd"
    const val PLACEHOLDER_REMOVE = "widget_placeholder_remove"
    const val MENU = "widget_menu"
    const val MENU_RESIZE = "widget_menu_resize"
    const val MENU_REPLACE = "widget_menu_replace"
    const val MENU_CLOCK = "widget_menu_clock"
    const val MENU_REMOVE = "widget_menu_remove"
    const val RESIZE_BAR = "widget_resize_bar"
    const val RESIZE_HANDLE = "widget_resize_handle"
    const val TALLER = "widget_resize_taller"
    const val SHORTER = "widget_resize_shorter"
    const val RESIZE_DONE = "widget_resize_done"
    const val PICKER = "widget_picker"
    const val PICKER_LIST = "widget_picker_list"
    const val PICKER_SEARCH = "widget_picker_search"
    const val CLOCK_DIALOG = "widget_clock_dialog"
    const val CLOCK_REPLACE = "widget_clock_replace"
    const val CLOCK_KEEP = "widget_clock_keep"

    fun provider(component: String) = "widget_provider_$component"

    fun group(packageName: String) = "widget_group_$packageName"
}

/** Height in dp of the area for [state] in a window [windowHeightDp] tall (FR-WIDGET-03). */
fun widgetHeightDp(
    state: WidgetAreaState,
    windowHeightDp: Int,
): Int =
    WidgetHeights.heightDp(
        state.placement?.heightStep ?: WidgetHeights.DEFAULT_STEP,
        state.minHeightDp,
        windowHeightDp,
    )

/**
 * The header widget area (FR-WIDGET-01/03/04, J-10). [hostContent] draws the hosted widget for an ID; it is supplied
 * by `:app`, the only module that unwraps a host view (ARCHITECTURE §3). A placement that is not available shows the
 * "Widget unavailable — Re-add / Remove" placeholder. Shows nothing in safe mode or without a placement.
 */
@Composable
fun WidgetArea(
    state: WidgetAreaState,
    onAction: (WidgetAreaAction) -> Unit,
    hostContent: @Composable (id: Int, onLongPress: () -> Unit, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val placement = state.placement
    if (!state.shown || placement == null) return
    if (!placement.available) {
        WidgetPlaceholder(onAction, modifier)
        return
    }
    val windowHeight = LocalConfiguration.current.screenHeightDp
    val height = widgetHeightDp(state, windowHeight)
    val currentOnAction by rememberUpdatedState(onAction)
    val description = stringResource(R.string.widget_area_description)
    val stepText = stringResource(R.string.widget_resize_step, placement.heightStep, WidgetHeights.MAX_STEP)
    val options = stringResource(R.string.widget_action_options)
    val taller = stringResource(R.string.widget_action_taller)
    val shorter = stringResource(R.string.widget_action_shorter)
    val remove = stringResource(R.string.widget_menu_remove)
    Column(modifier = modifier.fillMaxWidth().testTag(WidgetTestTags.AREA)) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(height.dp)
                    .clip(RoundedCornerShape(WIDGET_CORNER))
                    // INTERACTION_SPEC §9: Resize taller/shorter, Remove (plus the widget menu).
                    .semantics {
                        contentDescription = description
                        stateDescription = stepText
                        customActions =
                            listOf(
                                CustomAccessibilityAction(
                                    options,
                                ) { true.also { currentOnAction(WidgetAreaAction.OpenMenu) } },
                                CustomAccessibilityAction(
                                    taller,
                                ) { true.also { currentOnAction(WidgetAreaAction.Taller) } },
                                CustomAccessibilityAction(
                                    shorter,
                                ) { true.also { currentOnAction(WidgetAreaAction.Shorter) } },
                                CustomAccessibilityAction(
                                    remove,
                                ) { true.also { currentOnAction(WidgetAreaAction.Remove) } },
                            )
                    }.testTag(WidgetTestTags.HOST),
        ) {
            hostContent(placement.appWidgetId, { currentOnAction(WidgetAreaAction.OpenMenu) }, Modifier.fillMaxSize())
        }
        if (state.resizing) ResizeBar(state, windowHeight, onAction)
    }
}

/** J-10 resize: a vertical handle with 4 detents, plus Shorter / Taller buttons and Done. */
@Composable
private fun ResizeBar(
    state: WidgetAreaState,
    windowHeightDp: Int,
    onAction: (WidgetAreaAction) -> Unit,
) {
    val step = state.placement?.heightStep ?: WidgetHeights.DEFAULT_STEP
    val density = LocalDensity.current
    // One detent = the distance between two steps (at least 24 dp of drag).
    val detentPx =
        with(density) {
            val span =
                WidgetHeights.heightDp(WidgetHeights.MAX_STEP, state.minHeightDp, windowHeightDp) - state.minHeightDp
            (span / (WidgetHeights.MAX_STEP - WidgetHeights.MIN_STEP)).coerceAtLeast(MIN_DETENT_DP).dp.toPx()
        }
    val currentOnAction by rememberUpdatedState(onAction)
    var drag by remember { mutableFloatStateOf(0f) }
    val dragState =
        rememberDraggableState { delta ->
            drag += delta
            if (drag >= detentPx) {
                drag -= detentPx
                currentOnAction(WidgetAreaAction.Taller)
            } else if (drag <= -detentPx) {
                drag += detentPx
                currentOnAction(WidgetAreaAction.Shorter)
            }
        }
    val colors = Strata.colors
    Column(modifier = Modifier.fillMaxWidth().testTag(WidgetTestTags.RESIZE_BAR)) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 24.dp)
                    .draggable(dragState, Orientation.Vertical, onDragStopped = { drag = 0f })
                    .testTag(WidgetTestTags.RESIZE_HANDLE),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 32.dp, height = 4.dp).background(colors.accent, RoundedCornerShape(2.dp)))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                onClick = { onAction(WidgetAreaAction.Shorter) },
                enabled = step > WidgetHeights.MIN_STEP,
                modifier = Modifier.testTag(WidgetTestTags.SHORTER),
            ) { Text(stringResource(R.string.widget_resize_shorter)) }
            Text(
                text = stringResource(R.string.widget_resize_step, step, WidgetHeights.MAX_STEP),
                style = Strata.typography.label,
                color = colors.textPrimary,
            )
            TextButton(
                onClick = { onAction(WidgetAreaAction.Taller) },
                enabled = step < WidgetHeights.MAX_STEP,
                modifier = Modifier.testTag(WidgetTestTags.TALLER),
            ) { Text(stringResource(R.string.widget_resize_taller)) }
            TextButton(
                onClick = { onAction(WidgetAreaAction.DoneResize) },
                modifier = Modifier.testTag(WidgetTestTags.RESIZE_DONE),
            ) { Text(stringResource(R.string.widget_resize_done)) }
        }
    }
}

/** FR-WIDGET-04: "Widget unavailable — Re-add / Remove". */
@Composable
private fun WidgetPlaceholder(
    onAction: (WidgetAreaAction) -> Unit,
    modifier: Modifier,
) {
    val colors = Strata.colors
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(WIDGET_CORNER))
                .padding(16.dp)
                .testTag(WidgetTestTags.PLACEHOLDER),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.widget_unavailable),
            style = Strata.typography.label,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { onAction(WidgetAreaAction.ReAdd) },
                modifier = Modifier.heightIn(min = 48.dp).testTag(WidgetTestTags.READD),
            ) { Text(stringResource(R.string.widget_readd)) }
            TextButton(
                onClick = { onAction(WidgetAreaAction.Remove) },
                modifier = Modifier.heightIn(min = 48.dp).testTag(WidgetTestTags.PLACEHOLDER_REMOVE),
            ) { Text(stringResource(R.string.widget_remove)) }
        }
    }
}

/** The widget menu (J-10: long-press the widget → [Resize] [Replace] [Remove]), plus the clock choice. */
@Composable
fun WidgetMenu(
    state: WidgetAreaState,
    onAction: (WidgetAreaAction) -> Unit,
) {
    val placement = state.placement ?: return
    if (!state.menuOpen) return
    StrataSheet(
        onDismiss = { onAction(WidgetAreaAction.CloseMenu) },
        modifier = Modifier.testTag(WidgetTestTags.MENU),
    ) {
        if (placement.available) {
            SheetItem(
                stringResource(R.string.widget_menu_resize),
                { onAction(WidgetAreaAction.StartResize) },
                tag = WidgetTestTags.MENU_RESIZE,
            )
        }
        SheetItem(
            stringResource(R.string.widget_menu_replace),
            { onAction(WidgetAreaAction.Replace) },
            tag = WidgetTestTags.MENU_REPLACE,
        )
        if (placement.available) {
            SheetItem(
                stringResource(
                    if (placement.replaceClock) R.string.widget_menu_show_clock else R.string.widget_menu_hide_clock,
                ),
                { onAction(WidgetAreaAction.ToggleClock) },
                tag = WidgetTestTags.MENU_CLOCK,
            )
        }
        SheetItem(
            stringResource(R.string.widget_menu_remove),
            { onAction(WidgetAreaAction.Remove) },
            tag = WidgetTestTags.MENU_REMOVE,
        )
    }
}

/** An error tile for a widget whose view cannot be created (ANDROID_PLATFORM §7 "Failure"). */
@Composable
fun WidgetErrorTile(modifier: Modifier = Modifier) {
    val colors = Strata.colors
    Box(modifier = modifier.background(colors.surface).padding(16.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.widget_error),
            style = Strata.typography.label,
            color = colors.textSecondary,
        )
    }
}

private val WIDGET_CORNER = 12.dp
private const val MIN_DETENT_DP = 24
