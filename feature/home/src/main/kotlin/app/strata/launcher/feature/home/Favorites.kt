package app.strata.launcher.feature.home

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.StrokeCap
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
import app.strata.launcher.core.ui.AppPicker
import app.strata.launcher.core.ui.AppPickerTags
import app.strata.launcher.core.ui.AppPickerTexts
import app.strata.launcher.core.ui.Button
import app.strata.launcher.core.ui.IconRequestKey
import app.strata.launcher.core.ui.NotificationBadge
import app.strata.launcher.core.ui.OutlinedButton
import app.strata.launcher.core.ui.Strata
import app.strata.launcher.core.ui.TextButton
import app.strata.launcher.core.ui.WorkBadge
import app.strata.launcher.core.ui.apply
import app.strata.launcher.core.ui.focusRing
import app.strata.launcher.core.ui.rememberAppIconState
import app.strata.launcher.feature.home.list.originOf
import kotlin.math.roundToInt
import app.strata.launcher.core.theme.model.Alignment as ThemeAlignment

object FavEditTestTags {
    const val SCREEN = "fav_edit"
    const val DONE = "fav_edit_done"
    const val ANNOUNCEMENT = "fav_edit_announcement"
    const val ADD_APPS = "fav_edit_add_apps"
    const val PICKER = "fav_picker"
    const val PICKER_SEARCH = "fav_picker_search"
    const val PICKER_LIST = "fav_picker_list"
    const val PICKER_COUNT = "fav_picker_count"
    const val PICKER_LIMIT = "fav_picker_limit"
    const val PICKER_CONFIRM = "fav_picker_confirm"
    const val PICKER_CANCEL = "fav_picker_cancel"

    fun pick(key: AppKey) = "fav_picker_pick_${key.component}_${key.userSerial}"

    fun row(app: VisibleApp) = "fav_edit_row_${app.key.component}_${app.key.userSerial}"

    fun handle(app: VisibleApp) = "fav_edit_handle_${app.key.component}_${app.key.userSerial}"

    fun remove(app: VisibleApp) = "fav_edit_remove_${app.key.component}_${app.key.userSerial}"
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
                .focusRing()
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
 * S-FAVEDIT (FR-FAV-02, J-07), the one place to manage favourites: rows with drag handles (drag starts on the handle
 * at once, or anywhere on the row after a long-press) and a remove button each (removal shows at once, with Undo); a11y
 * actions Move up / Move down / Move to top / Remove with a polite live-region announcement; "Add apps" opens the
 * picker ([FavoritesPicker]). Done or Back saves.
 */
@Composable
fun FavoritesEditScreen(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val picker = state.favPicker
    if (picker != null) {
        FavoritesPicker(picker, onAction, modifier)
        return
    }
    val colors = Strata.colors
    val order = state.favEdit
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
                    RemoveButton(
                        description = stringResource(R.string.fav_remove_named, app.displayLabel),
                        onClick = { onAction(HomeAction.RemoveFromEdit(app.key)) },
                        modifier = Modifier.testTag(FavEditTestTags.remove(app)),
                    )
                }
            }
            item(key = "add_apps") {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    if (order.size >= FavoritesRepository.MAX) {
                        Text(
                            text =
                                pluralStringResource(
                                    R.plurals.fav_cap,
                                    FavoritesRepository.MAX,
                                    FavoritesRepository.MAX,
                                ),
                            style = Strata.typography.caption,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    OutlinedButton(
                        onClick = { onAction(HomeAction.OpenFavPicker) },
                        modifier = Modifier.testTag(FavEditTestTags.ADD_APPS),
                    ) { Text(text = stringResource(R.string.fav_add_apps)) }
                }
            }
        }
    }
}

/**
 * "Add apps" (FR-FAV-01/02): the shared checklist ([AppPicker]) over the visible apps (hidden apps and private space
 * left out), current favourites checked, the cap of 12 with the onboarding explanation. Confirm applies (new ones
 * appended); Cancel or Back leaves the favourites as they were.
 */
@Composable
private fun FavoritesPicker(
    picker: FavPickerUi,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onAction(HomeAction.FavPickerCancel) }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(Strata.colors.background)
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(FavEditTestTags.PICKER),
    ) {
        Text(
            text = stringResource(R.string.fav_picker_title),
            style = MaterialTheme.typography.titleLarge,
            color = Strata.colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        AppPicker(
            apps = picker.apps,
            query = picker.query,
            picked = picker.picked,
            limitReached = picker.limitReached,
            texts =
                AppPickerTexts(
                    count = stringResource(R.string.fav_picker_count, picker.picked.size, FavoritesRepository.MAX),
                    limit = stringResource(R.string.fav_picker_limit, FavoritesRepository.MAX),
                    search = stringResource(R.string.fav_picker_search),
                ),
            tags = PICKER_TAGS,
            onQuery = { onAction(HomeAction.FavPickerQuery(it)) },
            onToggle = { onAction(HomeAction.FavPickerToggle(it)) },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = { onAction(HomeAction.FavPickerCancel) },
                modifier = Modifier.testTag(FavEditTestTags.PICKER_CANCEL),
            ) { Text(text = stringResource(R.string.cancel)) }
            Button(
                onClick = { onAction(HomeAction.FavPickerConfirm) },
                modifier = Modifier.padding(start = 8.dp).testTag(FavEditTestTags.PICKER_CONFIRM),
            ) { Text(text = stringResource(R.string.done)) }
        }
    }
}

private val PICKER_TAGS =
    AppPickerTags(
        search = FavEditTestTags.PICKER_SEARCH,
        list = FavEditTestTags.PICKER_LIST,
        count = FavEditTestTags.PICKER_COUNT,
        limit = FavEditTestTags.PICKER_LIMIT,
        row = FavEditTestTags::pick,
    )

/** The row's remove control: a 48 dp target with an × and the name "Remove <label> from favourites". */
@Composable
private fun RemoveButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = Strata.colors.textPrimary
    Box(
        modifier =
            modifier
                .size(48.dp)
                .focusRing(CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(16.dp)) {
            val stroke = 2.dp.toPx()
            drawLine(color, Offset.Zero, Offset(size.width, size.height), stroke, StrokeCap.Round)
            drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
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
