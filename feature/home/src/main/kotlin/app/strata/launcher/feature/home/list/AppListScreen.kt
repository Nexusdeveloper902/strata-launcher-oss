package app.strata.launcher.feature.home.list

import android.os.Trace
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.BadgeStyle
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.apps.LaunchOrigin
import app.strata.launcher.core.theme.model.IndexGlyphs
import app.strata.launcher.core.theme.model.IndexSide
import app.strata.launcher.core.theme.model.IndexVisibility
import app.strata.launcher.core.ui.AlphabetIndex
import app.strata.launcher.core.ui.AppRow
import app.strata.launcher.core.ui.Strata
import app.strata.launcher.feature.home.R
import kotlinx.coroutines.launch

object AppListTestTags {
    const val LIST = "app_list"
    const val END_SETTINGS = "app_list_end_settings"
    const val END_HIDDEN = "app_list_end_hidden"
    const val EMPTY = "app_list_empty"

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
) {
    val theme = Strata.theme
    val colors = Strata.colors
    val scope = rememberCoroutineScope()
    val wallpaper = theme.wallpaper
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.scrim.copy(alpha = wallpaper.dim.toFloat()))
                .background(colors.background.copy(alpha = wallpaper.backgroundOpacity.toFloat()))
                .testTag(AppListTestTags.LIST),
    ) {
        val twoColumns = twoColumnSetting && maxWidth >= TWO_COLUMN_MIN_WIDTH
        val model =
            remember(list, twoColumns, theme.list.sectionHeaders) {
                ListItems.build(list, twoColumns, theme.list.sectionHeaders)
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

        if (list.appCount == 0 && list.hiddenCount > 0) {
            EmptyAllHidden(onOpenHiddenApps, Modifier.align(Alignment.Center))
        }

        LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
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
                        ListRow(item.app, badgeStyle, onLaunch, Modifier.fillMaxWidth())
                    }

                    is ListItem.Pair -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(GUTTER)) {
                            ListRow(item.start, badgeStyle, onLaunch, Modifier.weight(1f))
                            if (item.end != null) {
                                ListRow(item.end, badgeStyle, onLaunch, Modifier.weight(1f))
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }

                    is ListItem.End -> {
                        ListEnd(item.hiddenCount, onOpenSettings, onOpenHiddenApps)
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
                onSelect = { letter, first ->
                    val section = list.index[letter].sectionIndex
                    if (section in model.sectionStarts.indices) {
                        scope.launch { listState.scrollToItem(model.sectionStarts[section]) }
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

@Composable
private fun ListRow(
    app: VisibleApp,
    badgeStyle: BadgeStyle,
    onLaunch: (VisibleApp, LaunchOrigin?) -> Unit,
    modifier: Modifier,
) {
    val view = LocalView.current
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    LaunchedEffect(app.key) {
        Trace.beginSection(TRACE_ROW_COMPOSED)
        Trace.endSection()
    }
    AppRow(
        label = app.displayLabel,
        secondary = app.secondaryLine,
        profileKind = app.profileKind,
        badge = app.badge,
        badgeStyle = badgeStyle,
        onClick = { onLaunch(app, coordinates[0]?.let { originOf(it, view) }) },
        modifier =
            modifier
                .onGloballyPositioned { coordinates[0] = it }
                .testTag(AppListTestTags.row(app)),
    )
}

/** Row bounds in screen pixels: `boundsInWindow()` plus the window's location (ANDROID_PLATFORM §4). */
private fun originOf(
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
        EndRow(stringResource(R.string.list_end_hidden_apps, hiddenCount), onOpenHiddenApps, AppListTestTags.END_HIDDEN)
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
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = theme.list.horizontalPaddingDp.dp)
                .testTag(tag),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = text, style = Strata.typography.label, color = Strata.colors.textSecondary)
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

private val INDEX_RESERVE = 48.dp
private val INDEX_BOTTOM_GAP = 24.dp
private val GUTTER = 24.dp
