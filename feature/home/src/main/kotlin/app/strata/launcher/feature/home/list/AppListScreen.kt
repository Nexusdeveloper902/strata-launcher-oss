package app.strata.launcher.feature.home.list

import android.os.Trace
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.BadgeStyle
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.apps.LaunchOrigin
import app.strata.launcher.core.theme.model.IndexGlyphs
import app.strata.launcher.core.theme.model.IndexSide
import app.strata.launcher.core.theme.model.IndexVisibility
import app.strata.launcher.core.ui.AlphabetIndex
import app.strata.launcher.core.ui.AppRow
import app.strata.launcher.core.ui.FilterChip
import app.strata.launcher.core.ui.IndexExclusion
import app.strata.launcher.core.ui.IndexMath
import app.strata.launcher.core.ui.SearchPillFrame
import app.strata.launcher.core.ui.Strata
import app.strata.launcher.core.ui.Switch
import app.strata.launcher.core.ui.TextButton
import app.strata.launcher.core.ui.focusRing
import app.strata.launcher.core.ui.iconKey
import app.strata.launcher.feature.home.ListJump
import app.strata.launcher.feature.home.PrivateSpaceUi
import app.strata.launcher.feature.home.R
import app.strata.launcher.feature.home.WorkProfileUi
import kotlinx.coroutines.launch
import app.strata.launcher.core.ui.R as UiR

object AppListTestTags {
    const val LIST = "app_list"
    const val END_SETTINGS = "app_list_end_settings"
    const val END_HIDDEN = "app_list_end_hidden"
    const val EMPTY = "app_list_empty"
    const val SEARCH_FIELD = "app_list_search_field"
    const val WORK_BAR = "app_list_work_bar"
    const val WORK_FILTER_ALL = "app_list_work_filter_all"
    const val WORK_FILTER_WORK = "app_list_work_filter_work"
    const val WORK_SWITCH = "app_list_work_switch"
    const val PRIVATE_HEADER = "app_list_private_header"
    const val PRIVATE_UNLOCK = "app_list_private_unlock"
    const val PRIVATE_LOCK = "app_list_private_lock"
    const val PRIVATE_HIDE = "app_list_private_hide"
    const val PRIVATE_EMPTY = "app_list_private_empty"

    fun privateRow(app: VisibleApp) = "app_list_private_row_${app.key.component}_${app.key.userSerial}"

    fun row(app: VisibleApp) = "app_row_${app.key.component}_${app.key.userSerial}"

    fun header(label: String) = "app_list_header_$label"
}

/** Window width from which the list may use two columns (VISUAL_SPEC §8.2, FR-LIST-07). */
val TWO_COLUMN_MIN_WIDTH = 600.dp

/** Trace marker for T-PERF-10 (row composed after a package change). */
const val TRACE_ROW_COMPOSED = "strata.rowComposed"

/**
 * S-LIST (FR-LIST-01…07): the A–Z list over the effective background, with section headers, list-end
 * rows and the alphabet index on the theme's side.
 */
@Composable
fun AppListScreen(
    list: AppList,
    twoColumnSetting: Boolean,
    badgeStyle: BadgeStyle,
    onLaunch: (VisibleApp, LaunchOrigin?) -> Unit,
    onIndexScrub: (first: Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHiddenApps: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    /** The list's search field (VISUAL_SPEC §2, FR-SEARCH-01); absent when null. */
    onOpenSearch: (() -> Unit)? = null,
    /** Long-press on a row: the app sheet (FR-APPS-03). */
    onLongPress: ((VisibleApp) -> Unit)? = null,
    /** The sheet's actions per row as accessibility custom actions (FR-A11Y-01). */
    rowActions: (VisibleApp) -> List<CustomAccessibilityAction> = { emptyList() },
    /** A jump request from the home index or "Jump to letter…" (FR-A11Y-02). */
    jump: ListJump? = null,
    /** Called once [jump] has been applied, so the request is cleared (one-shot). */
    onJumpConsumed: (ListJump) -> Unit = {},
    /** "Jump to letter…" custom action on the list root (FR-A11Y-02); absent when null. */
    onJumpToLetterMenu: (() -> Unit)? = null,
    /** System gesture exclusion of the index (INTERACTION_SPEC §4.2). */
    indexExclusion: IndexExclusion = IndexExclusion.NONE,
    /** FR-APPS-09: the work bar (All / Work filter and the pause switch); absent without a work profile. */
    work: WorkProfileUi? = null,
    onWorkPaused: (Boolean) -> Unit = {},
    /** FR-APPS-10: the private-space container at the list end; absent when null. */
    privateSpace: PrivateSpaceUi? = null,
    onPrivateSpace: (PrivateSpaceCommand) -> Unit = {},
) {
    val theme = Strata.theme
    var workOnly by rememberSaveable { mutableStateOf(false) }
    if (work == null && workOnly) workOnly = false
    val all = list

    // The "Work" filter shows a rebuilt list of work apps only (FR-APPS-09).
    @Suppress("NAME_SHADOWING")
    val list = if (workOnly) remember(all) { all.onlyWork() } else all
    val scope = rememberCoroutineScope()
    // The backdrop E(W) is drawn once under every home layer (HomeRoute), so the list adds none of its own.
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .testTag(AppListTestTags.LIST),
    ) {
        val twoColumns = twoColumnSetting && maxWidth >= TWO_COLUMN_MIN_WIDTH
        val model =
            remember(list, twoColumns, theme.list.sectionHeaders, privateSpace, workOnly) {
                ListItems.build(list, twoColumns, theme.list.sectionHeaders, privateSpace.takeUnless { workOnly })
            }
        val showIndex = theme.alphabetIndex.visibility != IndexVisibility.NEVER && list.index.isNotEmpty()
        val atEnd = theme.alphabetIndex.side == IndexSide.END
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val direction = LocalLayoutDirection.current
        val indexReserve = if (showIndex) INDEX_RESERVE else 0.dp
        val contentPadding =
            PaddingValues(
                start = insets.calculateStartPadding(direction) + if (!atEnd) indexReserve else 0.dp,
                end = insets.calculateEndPadding(direction) + if (atEnd) indexReserve else 0.dp,
                top = insets.calculateTopPadding() + 8.dp,
                bottom = insets.calculateBottomPadding() + 96.dp,
            )

        // Remembered: a new instance on every recomposition of the list made every visible row recompose too
        // (RowCallbacks has identity equality; docs/perf/optimization-pass-1.md C-2).
        val row =
            remember(badgeStyle, onLaunch, onLongPress, rowActions) {
                RowCallbacks(badgeStyle, onLaunch, onLongPress, rowActions)
            }
        // Items before the first section: the search field and the work bar.
        val offset = (if (onOpenSearch != null) 1 else 0) + (if (work != null) 1 else 0)
        var focusTarget by remember { mutableStateOf<AppKey?>(null) }
        val currentOnJumpConsumed by rememberUpdatedState(onJumpConsumed)
        LaunchedEffect(jump) {
            val j = jump ?: return@LaunchedEffect
            val section = list.index.getOrNull(IndexMath.resolve(list.index, j.letter))?.sectionIndex
            if (section != null && section in model.sectionStarts.indices) {
                listState.scrollToItem(model.sectionStarts[section] + offset)
                if (j.focus) {
                    focusTarget =
                        list.sections[section]
                            .apps
                            .firstOrNull()
                            ?.key
                }
            }
            // One-shot: cleared after it is applied, so reopening or rebuilding the list does not jump back.
            currentOnJumpConsumed(j)
        }
        val jumpLabel = stringResource(R.string.list_jump_to_letter)

        if (list.appCount == 0 && list.hiddenCount > 0) {
            EmptyAllHidden(onOpenHiddenApps, Modifier.align(Alignment.Center))
        }

        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier =
                Modifier.fillMaxSize().semantics {
                    if (onJumpToLetterMenu != null) {
                        customActions =
                            listOf(
                                CustomAccessibilityAction(jumpLabel) {
                                    onJumpToLetterMenu()
                                    true
                                },
                            )
                    }
                },
        ) {
            if (onOpenSearch != null) {
                item(key = SEARCH_FIELD_KEY, contentType = SEARCH_FIELD_KEY) { ListSearchField(onOpenSearch) }
            }
            if (work != null) {
                item(key = WORK_BAR_KEY, contentType = WORK_BAR_KEY) {
                    WorkBar(work, workOnly, onFilter = { workOnly = it }, onPaused = onWorkPaused)
                }
            }
            items(
                count = model.items.size,
                key = { model.items[it].key },
                contentType = { model.items[it]::class },
            ) { i ->
                when (val item = model.items[i]) {
                    is ListItem.Header -> {
                        SectionHeader(item.label)
                    }

                    is ListItem.Row -> {
                        ListRow(item.app, row, Modifier.fillMaxWidth(), focus = item.app.key == focusTarget)
                    }

                    is ListItem.Pair -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(GUTTER)) {
                            ListRow(item.start, row, Modifier.weight(1f), focus = item.start.key == focusTarget)
                            if (item.end != null) {
                                ListRow(item.end, row, Modifier.weight(1f), focus = item.end.key == focusTarget)
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }

                    is ListItem.End -> {
                        ListEnd(item.hiddenCount, onOpenSettings, onOpenHiddenApps)
                    }

                    is ListItem.PrivateHeader -> {
                        PrivateHeader(item.locked, onPrivateSpace)
                    }

                    is ListItem.PrivateRow -> {
                        ListRow(
                            item.app,
                            row,
                            Modifier.fillMaxWidth(),
                            tag = AppListTestTags.privateRow(item.app),
                        )
                    }

                    ListItem.PrivateUnlock -> {
                        EndRow(
                            stringResource(R.string.private_space_unlock),
                            { onPrivateSpace(PrivateSpaceCommand.UNLOCK) },
                            AppListTestTags.PRIVATE_UNLOCK,
                        )
                    }

                    ListItem.PrivateEmpty -> {
                        Text(
                            text = stringResource(R.string.private_space_empty),
                            style = Strata.typography.label,
                            color = Strata.colors.textSecondary,
                            modifier =
                                Modifier
                                    .padding(horizontal = theme.list.horizontalPaddingDp.dp, vertical = 12.dp)
                                    .testTag(AppListTestTags.PRIVATE_EMPTY),
                        )
                    }
                }
            }
        }

        if (showIndex) {
            AlphabetIndex(
                letters = list.index,
                atEnd = atEnd,
                dots = theme.alphabetIndex.glyphs == IndexGlyphs.DOTS,
                showBubble = theme.alphabetIndex.showBubble,
                exclusion = indexExclusion,
                onSelect = { letter, first ->
                    val section = list.index[letter].sectionIndex
                    if (section in model.sectionStarts.indices) {
                        scope.launch { listState.scrollToItem(model.sectionStarts[section] + offset) }
                    }
                    onIndexScrub(first)
                },
                modifier =
                    Modifier
                        .align(if (atEnd) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(
                            top = insets.calculateTopPadding(),
                            bottom =
                                insets.calculateBottomPadding() + INDEX_BOTTOM_GAP,
                        ),
            )
        }
    }
}

/** The list's search field: the same pill shape; a tap opens S-SEARCH (VISUAL_SPEC §2, §9.1). */
@Composable
private fun ListSearchField(onOpenSearch: () -> Unit) {
    val theme = Strata.theme
    SearchPillFrame(
        onClick = onOpenSearch,
        modifier =
            Modifier
                .padding(horizontal = theme.list.horizontalPaddingDp.dp, vertical = 8.dp)
                .testTag(AppListTestTags.SEARCH_FIELD),
    ) {
        Text(
            text = stringResource(UiR.string.search_hint),
            style = Strata.typography.label,
            color = Strata.colors.textSecondary,
        )
    }
}

@Composable
private fun SectionHeader(label: String) {
    val theme = Strata.theme
    Text(
        text = label,
        style = Strata.typography.section,
        color = Strata.colors.textSecondary,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .padding(start = theme.list.horizontalPaddingDp.dp, end = theme.list.horizontalPaddingDp.dp, top = 8.dp)
                .semantics { heading() }
                .testTag(AppListTestTags.header(label)),
    )
}

/** Per-row callbacks shared by every row of one list. */
private class RowCallbacks(
    val badgeStyle: BadgeStyle,
    val onLaunch: (VisibleApp, LaunchOrigin?) -> Unit,
    val onLongPress: ((VisibleApp) -> Unit)?,
    val actions: (VisibleApp) -> List<CustomAccessibilityAction>,
)

@Composable
private fun ListRow(
    app: VisibleApp,
    callbacks: RowCallbacks,
    modifier: Modifier,
    focus: Boolean = false,
    tag: String = AppListTestTags.row(app),
) {
    val view = LocalView.current
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    LaunchedEffect(app.key) {
        Trace.beginSection(TRACE_ROW_COMPOSED)
        Trace.endSection()
    }
    val focusRequester = remember { FocusRequester() }
    if (focus) LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    // A fresh list per call would make AppRow's `actions` differ on every recomposition (C-3).
    val actions = remember(app, callbacks.actions) { callbacks.actions(app) }
    AppRow(
        label = app.displayLabel,
        secondary = app.secondaryLine,
        profileKind = app.profileKind,
        badge = app.badge,
        badgeStyle = callbacks.badgeStyle,
        onClick = { callbacks.onLaunch(app, coordinates[0]?.let { originOf(it, view) }) },
        onLongClick = callbacks.onLongPress?.let { { it(app) } },
        actions = actions,
        icon = app.iconKey(),
        // FR-LIST-08 suspended and FR-APPS-09 paused: icon at 60 % α, label kept, state named by a tag.
        dimIcon = app.suspended || app.paused,
        tag =
            when {
                app.suspended -> stringResource(R.string.row_suspended)
                app.paused -> stringResource(R.string.row_paused)
                else -> null
            },
        modifier =
            modifier
                .onGloballyPositioned { coordinates[0] = it }
                .focusRequester(focusRequester)
                .testTag(tag),
    )
}

/** Row bounds in screen pixels: `boundsInWindow()` plus the window's location (ANDROID_PLATFORM §4). */
internal fun originOf(
    coordinates: LayoutCoordinates,
    view: android.view.View,
): LaunchOrigin? {
    if (!coordinates.isAttached) return null
    val bounds = coordinates.boundsInWindow()
    val location = IntArray(2)
    view.rootView.getLocationOnScreen(location)
    return LaunchOrigin(
        left = location[0] + bounds.left.toInt(),
        top = location[1] + bounds.top.toInt(),
        right = location[0] + bounds.right.toInt(),
        bottom = location[1] + bounds.bottom.toInt(),
    )
}

/** FR-LIST-06: "Settings" and "Hidden apps (n)". */
@Composable
private fun ListEnd(
    hiddenCount: Int,
    onOpenSettings: () -> Unit,
    onOpenHiddenApps: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        EndRow(stringResource(R.string.list_end_settings), onOpenSettings, AppListTestTags.END_SETTINGS)
        EndRow(
            pluralStringResource(R.plurals.list_end_hidden_apps, hiddenCount, hiddenCount),
            onOpenHiddenApps,
            AppListTestTags.END_HIDDEN,
        )
    }
}

@Composable
private fun EndRow(
    text: String,
    onClick: () -> Unit,
    tag: String,
) {
    val theme = Strata.theme
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .focusRing()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = theme.list.horizontalPaddingDp.dp)
                .testTag(tag),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = text, style = Strata.typography.label, color = Strata.colors.textSecondary)
    }
}

/** FR-APPS-10 container commands. */
enum class PrivateSpaceCommand { LOCK, UNLOCK, HIDE }

/**
 * FR-APPS-09: "All / Work" filter and the work-apps switch (on = running, off = paused). Profile headings are headings
 * for TalkBack (INTERACTION_SPEC §9); the state is spoken by the switch, never shown by colour alone.
 */
@Composable
private fun WorkBar(
    work: WorkProfileUi,
    workOnly: Boolean,
    onFilter: (Boolean) -> Unit,
    onPaused: (Boolean) -> Unit,
) {
    val theme = Strata.theme
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = theme.list.horizontalPaddingDp.dp, vertical = 4.dp)
                .testTag(AppListTestTags.WORK_BAR),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = !workOnly,
            onClick = { onFilter(false) },
            label = { Text(stringResource(R.string.work_bar_all)) },
            modifier = Modifier.testTag(AppListTestTags.WORK_FILTER_ALL),
        )
        FilterChip(
            selected = workOnly,
            onClick = { onFilter(true) },
            label = { Text(stringResource(R.string.work_bar_work)) },
            modifier = Modifier.testTag(AppListTestTags.WORK_FILTER_WORK),
        )
        Spacer(Modifier.weight(1f))
        Row(
            modifier =
                Modifier
                    .heightIn(min = 48.dp)
                    .focusRing()
                    .toggleable(value = !work.paused, role = Role.Switch, onValueChange = { on ->
                        onPaused(!on)
                    })
                    .testTag(AppListTestTags.WORK_SWITCH),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.work_bar_switch),
                style = Strata.typography.label,
                color = Strata.colors.textPrimary,
            )
            Switch(checked = !work.paused, onCheckedChange = null)
        }
    }
}

/** FR-APPS-10: the container heading with Lock (while unlocked) and Hide. */
@Composable
private fun PrivateHeader(
    locked: Boolean,
    onCommand: (PrivateSpaceCommand) -> Unit,
) {
    val theme = Strata.theme
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = theme.list.horizontalPaddingDp.dp, end = 8.dp, top = 16.dp)
                .testTag(AppListTestTags.PRIVATE_HEADER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.private_space_title),
            style = Strata.typography.section,
            color = Strata.colors.textSecondary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (!locked) {
            TextButton(
                onClick = { onCommand(PrivateSpaceCommand.LOCK) },
                modifier = Modifier.testTag(AppListTestTags.PRIVATE_LOCK),
            ) { Text(stringResource(R.string.private_space_lock)) }
        }
        TextButton(
            onClick = { onCommand(PrivateSpaceCommand.HIDE) },
            modifier = Modifier.testTag(AppListTestTags.PRIVATE_HIDE),
        ) { Text(stringResource(R.string.private_space_hide)) }
    }
}

/** INTERACTION_SPEC §8 "All apps are hidden". */
@Composable
private fun EmptyAllHidden(
    onOpenHiddenApps: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.width(280.dp).testTag(AppListTestTags.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.list_all_hidden),
            style = Strata.typography.label,
            color = Strata.colors.textPrimary,
        )
        TextButton(onClick = onOpenHiddenApps) { Text(text = stringResource(R.string.list_manage_hidden)) }
    }
}

private const val SEARCH_FIELD_KEY = "search_field"
private const val WORK_BAR_KEY = "work_bar"
private val INDEX_RESERVE = 48.dp
private val INDEX_BOTTOM_GAP = 24.dp
private val GUTTER = 24.dp
