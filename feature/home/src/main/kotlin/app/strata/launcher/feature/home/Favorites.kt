package app.strata.launcher.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.strata.launcher.core.model.AppFlags
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.BadgeStyle
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.apps.FavoritesRepository
import app.strata.launcher.core.platformapi.apps.LaunchOrigin
import app.strata.launcher.core.ui.AppIcon
import app.strata.launcher.core.ui.IconRequestKey
import app.strata.launcher.core.ui.NotificationBadge
import app.strata.launcher.core.ui.Strata
import app.strata.launcher.core.ui.WorkBadge
import app.strata.launcher.core.ui.apply
import app.strata.launcher.core.ui.rememberAppIconState
import app.strata.launcher.feature.home.list.originOf
import kotlin.math.roundToInt
import app.strata.launcher.core.theme.model.Alignment as ThemeAlignment

object FavEditTestTags {
    const val SCREEN = "fav_edit"
    const val DONE = "fav_edit_done"
    const val ANNOUNCEMENT = "fav_edit_announcement"

    fun row(app: VisibleApp) = "fav_edit_row_${app.key.component}_${app.key.userSerial}"

    fun handle(app: VisibleApp) = "fav_edit_handle_${app.key.component}_${app.key.userSerial}"

    fun remove(app: VisibleApp) = "fav_edit_remove_${app.key.component}_${app.key.userSerial}"

    fun add(app: VisibleApp) = "fav_edit_add_${app.key.component}_${app.key.userSerial}"
}

/** `type.favorite` (VISUAL_SPEC §3): the list label × `favorites.textScale`. */
@Composable
internal fun favoriteTextStyle(): TextStyle {
    val label = Strata.typography.label
    val scale =
        Strata.theme.favorites.textScale
            .toFloat()
    return label.copy(fontSize = label.fontSize * scale, lineHeight = label.lineHeight * scale)
}

@Composable
internal fun favoriteAlignment(): Alignment =
    when (Strata.theme.favorites.alignment) {
        ThemeAlignment.START -> Alignment.CenterStart
        ThemeAlignment.CENTER -> Alignment.Center
        ThemeAlignment.END -> Alignment.CenterEnd
    }

/**
 * One favourite (VISUAL_SPEC §9.2): text-first, label in `type.favorite`, min 48 dp, optional icon at 1.25 × the
 * list icon size, badges after the label. One accessibility node with the sheet actions (FR-A11Y-01).
 */
@Composable
fun FavoriteRow(
    app: VisibleApp,
    badgeStyle: BadgeStyle,
    onLaunch: (LaunchOrigin?) -> Unit,
    onLongPress: () -> Unit,
    actions: List<CustomAccessibilityAction>,
    modifier: Modifier = Modifier,
) {
    val theme = Strata.theme
    val favorites = theme.favorites
    val view = LocalView.current
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val locale = LocalLocale.current.platformLocale
    val arrangement =
        when (favorites.alignment) {
            ThemeAlignment.START -> Arrangement.spacedBy(12.dp, Alignment.Start)
            ThemeAlignment.CENTER -> Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ThemeAlignment.END -> Arrangement.spacedBy(12.dp, Alignment.End)
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .onGloballyPositioned { coordinates[0] = it }
                .combinedClickable(
                    role = Role.Button,
                    onLongClick = onLongPress,
                    onClick = { onLaunch(coordinates[0]?.let { originOf(it, view) }) },
                ).semantics {
                    contentDescription = app.displayLabel
                    if (actions.isNotEmpty()) customActions = actions
                }.padding(horizontal = theme.list.horizontalPaddingDp.dp),
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var systemIcon = false
        if (favorites.showIcons) {
            val size = (theme.icons.sizeDp * FAVORITE_ICON_SCALE).roundToInt().dp
            val iconState = rememberAppIconState(IconRequestKey(app.key, app.lastUpdateTime, app.iconOverride), size)
            AppIcon(
                label = app.displayLabel,
                state = iconState,
                size = size,
                shape = androidx.compose.foundation.shape.CircleShape,
            )
            systemIcon = iconState.isSystemIcon
        }
        if (favorites.showLabels) {
            Text(
                text = theme.typography.labelCase.apply(app.displayLabel, locale),
                style = favoriteTextStyle(),
                color = Strata.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (app.profileKind == ProfileKind.WORK && !systemIcon) WorkBadge()
        if (app.badge > 0) NotificationBadge(count = app.badge, style = badgeStyle)
    }
}

/**
 * The sheet actions as custom accessibility actions (FR-A11Y-01): Favourite/Unfavourite, Rename, Hide/Show,
 * Change icon, App info, Uninstall (or Uninstall updates; absent for system apps). Shared by list, favourites and
 * search (`:app` passes it to the search layer).
 */
@Composable
fun rememberAppActions(
    favoriteKeys: Set<AppKey>,
    onAction: (HomeAction) -> Unit,
): (VisibleApp) -> List<CustomAccessibilityAction> {
    val labels =
        ActionLabels(
            favorite = stringResource(R.string.sheet_add_favorite),
            unfavorite = stringResource(R.string.sheet_remove_favorite),
            rename = stringResource(R.string.sheet_rename),
            hide = stringResource(R.string.sheet_hide),
            unhide = stringResource(R.string.sheet_unhide),
            changeIcon = stringResource(R.string.sheet_change_icon),
            appInfo = stringResource(R.string.sheet_app_info),
            uninstall = stringResource(R.string.sheet_uninstall),
            uninstallUpdates = stringResource(R.string.sheet_uninstall_updates),
        )
    val current by rememberUpdatedState(onAction)
    return remember(labels, favoriteKeys) {
        { app -> appActions(app, app.key in favoriteKeys, labels) { current(it) } }
    }
}

internal data class ActionLabels(
    val favorite: String,
    val unfavorite: String,
    val rename: String,
    val hide: String,
    val unhide: String,
    val changeIcon: String,
    val appInfo: String,
    val uninstall: String,
    val uninstallUpdates: String,
)

/** Which uninstall item an app gets (FR-APPS-03, ANDROID_PLATFORM §5). */
enum class UninstallKind { NONE, UNINSTALL, UNINSTALL_UPDATES }

fun uninstallKind(app: VisibleApp): UninstallKind =
    when {
        app.hasFlag(AppFlags.UPDATED_SYSTEM) -> UninstallKind.UNINSTALL_UPDATES
        app.hasFlag(AppFlags.SYSTEM) -> UninstallKind.NONE
        else -> UninstallKind.UNINSTALL
    }

internal fun appActions(
    app: VisibleApp,
    favorite: Boolean,
    labels: ActionLabels,
    onAction: (HomeAction) -> Unit,
): List<CustomAccessibilityAction> {
    fun action(
        label: String,
        a: HomeAction,
    ) = CustomAccessibilityAction(label) {
        onAction(a)
        true
    }
    // FR-APPS-10: a private-space app offers only the system flows (nothing about it may be stored).
    val stores = !app.isPrivate
    return listOfNotNull(
        if (stores) {
            action(
                if (favorite) labels.unfavorite else labels.favorite,
                HomeAction.ToggleFavorite(app.key),
            )
        } else {
            null
        },
        if (stores) action(labels.rename, HomeAction.OpenSheet(app, rename = true)) else null,
        if (stores) {
            action(
                if (app.hidden) labels.unhide else labels.hide,
                HomeAction.SetHidden(app.key, !app.hidden),
            )
        } else {
            null
        },
        if (stores) action(labels.changeIcon, HomeAction.ChangeIcon(app.key)) else null,
        action(labels.appInfo, HomeAction.OpenAppInfo(app.key, null)),
        when (uninstallKind(app)) {
            UninstallKind.UNINSTALL -> action(labels.uninstall, HomeAction.Uninstall(app.key))
            UninstallKind.UNINSTALL_UPDATES -> action(labels.uninstallUpdates, HomeAction.Uninstall(app.key))
            UninstallKind.NONE -> null
        },
    )
}

/**
 * S-FAVEDIT (FR-FAV-02, J-07): favourites with drag handles (drag starts on the handle at once, or anywhere on the
 * row after a long-press) and remove buttons; a11y actions Move up / Move down / Move to top / Remove with a polite
 * live-region announcement; then the other apps with "Add" (the picker for an empty home). Done or Back saves.
 */
@Composable
fun FavoritesEditScreen(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = Strata.theme
    val colors = Strata.colors
    val order = state.favEdit
    val inEdit = order.map { it.key }.toSet()
    val others =
        state.list
            ?.sections
            ?.flatMap { it.apps }
            ?.filterNot { it.key in inEdit }
            .orEmpty()
    var dragging by remember { mutableStateOf<AppKey?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(1) }
    val currentOrder by rememberUpdatedState(order)
    val currentOnAction by rememberUpdatedState(onAction)

    fun dragBy(
        key: AppKey,
        dy: Float,
    ) {
        dragOffset += dy
        val i = currentOrder.indexOfFirst { it.key == key }
        val half = rowHeight / 2f
        if (dragOffset > half && i in 0 until currentOrder.size - 1) {
            currentOnAction(HomeAction.MoveFavorite(i, i + 1))
            dragOffset -= rowHeight
        } else if (dragOffset < -half && i > 0) {
            currentOnAction(HomeAction.MoveFavorite(i, i - 1))
            dragOffset += rowHeight
        }
    }

    fun start(key: AppKey) {
        dragging = key
        dragOffset = 0f
        currentOnAction(HomeAction.FavoriteDrag(started = true))
    }

    fun end() {
        dragging = null
        dragOffset = 0f
        currentOnAction(HomeAction.FavoriteDrag(started = false))
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background)
                .safeDrawingPadding()
                .testTag(FavEditTestTags.SCREEN),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.menu_edit_favorites),
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TextButton(
                onClick = { onAction(HomeAction.DoneEditing) },
                modifier = Modifier.testTag(FavEditTestTags.DONE),
            ) { Text(text = stringResource(R.string.done)) }
        }
        val announcement = state.favEditAnnouncement
        Text(
            text =
                if (announcement != null) {
                    stringResource(R.string.fav_moved, announcement.label, announcement.position, announcement.count)
                } else {
                    ""
                },
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier =
                Modifier
                    .padding(horizontal = 16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(FavEditTestTags.ANNOUNCEMENT),
        )
        val moveUp = stringResource(R.string.fav_move_up)
        val moveDown = stringResource(R.string.fav_move_down)
        val moveTop = stringResource(R.string.fav_move_top)
        val remove = stringResource(R.string.fav_remove)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(order, key = { _, app -> "f:" + app.key.component + ":" + app.key.userSerial }) { i, app ->
                val isDragged = dragging == app.key
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .onSizeChanged { if (it.height > 0) rowHeight = it.height }
                            .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                            .background(if (isDragged) colors.highlight else colors.background)
                            .pointerDrag(app.key, ::start, ::dragBy, ::end, afterLongPress = true)
                            .semantics(mergeDescendants = true) {
                                contentDescription = app.displayLabel
                                customActions =
                                    listOfNotNull(
                                        if (i >
                                            0
                                        ) {
                                            a11y(moveUp) { onAction(HomeAction.MoveFavoriteBy(app.key, -1)) }
                                        } else {
                                            null
                                        },
                                        if (i <
                                            order.size - 1
                                        ) {
                                            a11y(moveDown) { onAction(HomeAction.MoveFavoriteBy(app.key, 1)) }
                                        } else {
                                            null
                                        },
                                        if (i >
                                            0
                                        ) {
                                            a11y(moveTop) { onAction(HomeAction.MoveFavoriteToTop(app.key)) }
                                        } else {
                                            null
                                        },
                                        a11y(remove) { onAction(HomeAction.RemoveFromEdit(app.key)) },
                                    )
                            }.padding(horizontal = 8.dp)
                            .testTag(FavEditTestTags.row(app)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DragHandle(
                        modifier =
                            Modifier
                                .size(48.dp)
                                .pointerDrag(app.key, ::start, ::dragBy, ::end, afterLongPress = false)
                                .testTag(FavEditTestTags.handle(app)),
                    )
                    Text(
                        text = app.displayLabel,
                        style = Strata.typography.label,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = { onAction(HomeAction.RemoveFromEdit(app.key)) },
                        modifier = Modifier.testTag(FavEditTestTags.remove(app)),
                    ) { Text(text = remove) }
                }
            }
            item(key = "add_header") {
                Text(
                    text =
                        if (order.size < FavoritesRepository.MAX) {
                            stringResource(R.string.fav_add_apps)
                        } else {
                            pluralStringResource(R.plurals.fav_cap, FavoritesRepository.MAX, FavoritesRepository.MAX)
                        },
                    style = Strata.typography.section,
                    color = colors.textSecondary,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
                            .semantics { heading() },
                )
            }
            if (order.size < FavoritesRepository.MAX) {
                items(others, key = { "o:" + it.key.component + ":" + it.key.userSerial }) { app ->
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { onAction(HomeAction.AddInEdit(app.key)) }
                                .padding(horizontal = theme.list.horizontalPaddingDp.dp)
                                .testTag(FavEditTestTags.add(app)),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = stringResource(R.string.fav_add_app, app.displayLabel),
                            style = Strata.typography.label,
                            color = colors.textPrimary,
                        )
                    }
                }
            }
        }
    }
}

private fun a11y(
    label: String,
    block: () -> Unit,
) = CustomAccessibilityAction(label) {
    block()
    true
}

private fun Modifier.pointerDrag(
    key: AppKey,
    onStart: (AppKey) -> Unit,
    onDrag: (AppKey, Float) -> Unit,
    onEnd: () -> Unit,
    afterLongPress: Boolean,
): Modifier =
    pointerInput(key, afterLongPress) {
        if (afterLongPress) {
            detectDragGesturesAfterLongPress(
                onDragStart = { onStart(key) },
                onDragEnd = onEnd,
                onDragCancel = onEnd,
                onDrag = { change, amount ->
                    change.consume()
                    onDrag(key, amount.y)
                },
            )
        } else {
            detectDragGestures(
                onDragStart = { onStart(key) },
                onDragEnd = onEnd,
                onDragCancel = onEnd,
                onDrag = { change, amount ->
                    change.consume()
                    onDrag(key, amount.y)
                },
            )
        }
    }

/** Six-dot grip drawn in code (decorative; the row carries the actions). */
@Composable
private fun DragHandle(modifier: Modifier) {
    val color = Strata.colors.textSecondary
    Canvas(modifier = modifier) {
        val r = 2.dp.toPx()
        val gap = 6.dp.toPx()
        for (col in 0..1) {
            for (row in -1..1) {
                drawCircle(color, r, Offset(size.width / 2f + (col - 0.5f) * gap, size.height / 2f + row * gap))
            }
        }
    }
}

private const val FAVORITE_ICON_SCALE = 1.25f
