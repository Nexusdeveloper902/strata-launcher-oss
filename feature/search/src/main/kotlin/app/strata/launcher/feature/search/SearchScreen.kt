package app.strata.launcher.feature.search

import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.strata.launcher.core.model.BadgeStyle
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.apps.LaunchOrigin
import app.strata.launcher.core.ui.AppRow
import app.strata.launcher.core.ui.IconRequestKey
import app.strata.launcher.core.ui.SearchPillFrame
import app.strata.launcher.core.ui.Strata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import app.strata.launcher.core.ui.R as UiR

object SearchTestTags {
    const val LAYER = "search_layer"
    const val FIELD = "search_field"
    const val CLEAR = "search_clear"
    const val RESULTS = "search_results"
    const val RECENT_HEADER = "search_recent_header"
    const val NO_RESULTS = "search_no_results"
    const val STORE_ROW = "search_store_row"

    fun row(app: VisibleApp) = "search_row_${app.key.component}_${app.key.userSerial}"
}

/**
 * The search layer with its effects (snackbar for launch failures, close). [session] changes every time
 * the layer opens; the field then starts with [seed] (a printable key typed on home/list, FR-SEARCH-01).
 * [closeLog] receives one line per Back/IME close decision (booleans only, never the query); the app routes it
 * to its debug-level logger, so it is logcat-only in debug builds (docs/impl-notes/M2.md item 17).
 */
@Composable
fun SearchRoute(
    state: SearchUiState,
    effects: Flow<SearchEffect>,
    onAction: (SearchAction) -> Unit,
    onClose: () -> Unit,
    seed: String,
    session: Int,
    modifier: Modifier = Modifier,
    /** Long-press on a result: the app sheet, owned by home (FR-APPS-03, T-APPS-05). */
    onLongPress: (VisibleApp) -> Unit = {},
    /** The sheet's actions per result as accessibility custom actions (FR-A11Y-01). */
    rowActions: (VisibleApp) -> List<CustomAccessibilityAction> = { emptyList() },
    closeLog: (String) -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val currentCloseLog by rememberUpdatedState(closeLog)
    val resources = LocalResources.current
    val currentOnAction by rememberUpdatedState(onAction)
    val currentOnClose by rememberUpdatedState(onClose)
    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                SearchEffect.Close -> {
                    currentCloseLog("onClose")
                    currentOnClose()
                }

                is SearchEffect.LaunchFailed -> {
                    launch {
                        val result =
                            snackbar.showSnackbar(
                                message = resources.getString(R.string.search_launch_failed, effect.label),
                                actionLabel = resources.getString(R.string.search_launch_failed_app_info),
                                duration = SnackbarDuration.Long,
                            )
                        if (result ==
                            SnackbarResult.ActionPerformed
                        ) {
                            currentOnAction(SearchAction.OpenAppInfo(effect.key))
                        }
                    }
                }
            }
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        SearchScreen(
            state = state,
            onAction = onAction,
            seed = seed,
            session = session,
            onLongPress = onLongPress,
            rowActions = rowActions,
            closeLog = closeLog,
        )
        SnackbarHost(
            hostState = snackbar,
            modifier =
                Modifier
                    .align(
                        Alignment.BottomCenter,
                    ).padding(WindowInsets.safeDrawing.union(WindowInsets.ime).asPaddingValues()),
        )
    }
}

/**
 * S-SEARCH (VISUAL_SPEC §9.1, INTERACTION_SPEC §5): the field at the top (the pill rises into it), the
 * ranked results below, padded by the IME inset. Keyboard: Up/Down move the selection ring, Enter / IME Go
 * launch the selection, Esc closes. Back with the IME up hides it first (system); with an empty query the
 * IME hiding closes the layer (one Back). Predictive back fades the layer and restores it on cancel.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    onAction: (SearchAction) -> Unit,
    seed: String,
    session: Int,
    modifier: Modifier = Modifier,
    onLongPress: (VisibleApp) -> Unit = {},
    rowActions: (VisibleApp) -> List<CustomAccessibilityAction> = { emptyList() },
    closeLog: (String) -> Unit = {},
) {
    val theme = Strata.theme
    val colors = Strata.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var field by remember(session) { mutableStateOf(TextFieldValue(seed, TextRange(seed.length))) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    val imeVisible = WindowInsets.isImeVisible
    val imeVisibleNow by rememberUpdatedState(imeVisible)
    val imeClose = remember { ImeCloseTracker() }

    // Open: seed the query. This runs when the layer's first composition is applied, before any input can reach
    // the field. It used to run in the LaunchedEffect below, whose body can start hundreds of ms later on a loaded
    // device and then wiped text typed in between (docs/impl-notes/M2.md item 18, CI run 41709431553).
    DisposableEffect(session) {
        onAction(SearchAction.Open(seed))
        onDispose {}
    }

    // Focus the field and show the IME; retry once on the next frame (ANDROID_PLATFORM §14).
    LaunchedEffect(session) {
        focus.requestFocus()
        keyboard?.show()
        withFrameNanos { }
        withFrameNanos { }
        if (!imeVisibleNow) keyboard?.show()
    }

    // Results scrolled by touch hide the IME; the query stays (INTERACTION_SPEC §5).
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) {
                imeClose.onResultsDrag()
                keyboard?.hide()
            }
        }
    }

    // Empty query: the Back that hides the IME also closes search (INTERACTION_SPEC §5 "Dismissal"). On API 26
    // Gboard consumes that Back, so this IME-hide edge is the only close path. How the IME is observed, and
    // why, is in [ImeHideClosesSearch] (docs/impl-notes/M2.md items 16–18).
    val currentCloseLog by rememberUpdatedState(closeLog)
    ImeHideClosesSearch(
        tracker = imeClose,
        // The field's own text, not the VM state (which follows it asynchronously): what the user sees is
        // what decides.
        queryBlank = { field.text.isBlank() },
        onClose = { onAction(SearchAction.Close) },
        log = closeLog,
    )

    PredictiveBackHandler(enabled = true) { progress ->
        try {
            progress.collect { backProgress = it.progress }
            currentCloseLog("back handler -> Close")
            onAction(SearchAction.Close)
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }

    // Selection follows the keyboard (keep the ring on screen).
    LaunchedEffect(state.selected, state.items.size, state.query) {
        if (state.items.isNotEmpty()) {
            val target = state.selected + if (state.isBlank && state.recents.isNotEmpty()) 1 else 0
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.none { it.index == target }) listState.animateScrollToItem(target)
        }
    }

    val insets = WindowInsets.safeDrawing.asPaddingValues()
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .alpha(1f - backProgress * BACK_FADE)
                .background(colors.scrim.copy(alpha = theme.wallpaper.dim.toFloat()))
                .background(colors.background.copy(alpha = theme.wallpaper.backgroundOpacity.toFloat()))
                .testTag(SearchTestTags.LAYER),
    ) {
        SearchField(
            value = field,
            onValueChange = {
                field = it
                if (it.text != state.query) onAction(SearchAction.QueryChanged(it.text))
            },
            onAction = onAction,
            showHint = theme.search.showHint,
            focus = focus,
            modifier = Modifier.padding(top = insets.calculateTopPadding() + 8.dp, start = 16.dp, end = 16.dp),
        )
        val bottom =
            WindowInsets.safeDrawing
                .union(WindowInsets.ime)
                .asPaddingValues()
                .calculateBottomPadding()
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 8.dp, bottom = bottom + 16.dp),
            modifier = Modifier.fillMaxSize().testTag(SearchTestTags.RESULTS),
        ) {
            if (state.isBlank && state.recents.isNotEmpty()) {
                item(key = "recent_header") { RecentHeader() }
            }
            val items = state.items
            items(
                count = items.size,
                key = { "app_" + items[it].key.component + "_" + items[it].key.userSerial },
            ) { i ->
                ResultRow(
                    app = items[i],
                    selected = i == state.selected,
                    badgeStyle = state.badgeStyle,
                    onLaunch = { app, origin -> onAction(SearchAction.Launch(app, origin)) },
                    onLongPress = onLongPress,
                    actions = rowActions(items[i]),
                )
            }
            if (state.noResults) {
                item(key = "no_results") { NoResults(state.query) }
                if (state.showStoreRow) item(key = "store") { StoreRow { onAction(SearchAction.StoreSearch) } }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onAction: (SearchAction) -> Unit,
    showHint: Boolean,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val colors = Strata.colors
    val type = Strata.typography
    val fieldDescription = stringResource(R.string.search_field_description)
    SearchPillFrame(modifier = modifier) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.text.isEmpty() && showHint) {
                Text(text = stringResource(UiR.string.search_hint), style = type.label, color = colors.textSecondary)
            }
            // IME_FLAG_NO_PERSONALIZED_LEARNING (INTERACTION_SPEC §5): app names never train the keyboard.
            InterceptPlatformTextInput(
                interceptor = { request, next ->
                    next.startInputMethod(NoLearningRequest(request))
                },
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = type.label.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Go,
                        ),
                    keyboardActions = KeyboardActions(onGo = { onAction(SearchAction.LaunchSelected) }),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (e.key) {
                                    Key.DirectionDown -> onAction(SearchAction.MoveSelection(1))
                                    Key.DirectionUp -> onAction(SearchAction.MoveSelection(-1))
                                    Key.Enter, Key.NumPadEnter -> onAction(SearchAction.LaunchSelected)
                                    Key.Escape -> onAction(SearchAction.Close)
                                    else -> return@onPreviewKeyEvent false
                                }
                                true
                            }.semantics { contentDescription = fieldDescription }
                            .testTag(SearchTestTags.FIELD),
                )
            }
        }
        if (value.text.isNotEmpty()) {
            ClearButton { onValueChange(TextFieldValue("")) }
        }
    }
}

/** Adds `IME_FLAG_NO_PERSONALIZED_LEARNING` to the editor info of the wrapped request. */
@OptIn(ExperimentalComposeUiApi::class)
private class NoLearningRequest(
    private val delegate: PlatformTextInputMethodRequest,
) : PlatformTextInputMethodRequest {
    override fun createInputConnection(outAttributes: EditorInfo) =
        delegate.createInputConnection(outAttributes).also {
            outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
}

@Composable
private fun ClearButton(onClick: () -> Unit) {
    val color = Strata.colors.textSecondary
    val description = stringResource(R.string.search_clear)
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description }
                .testTag(SearchTestTags.CLEAR),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(14.dp)) {
            val w = 2.dp.toPx()
            drawLine(color, Offset.Zero, Offset(size.width, size.height), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun RecentHeader() {
    val theme = Strata.theme
    Text(
        text = stringResource(R.string.search_recent_header),
        style = Strata.typography.section,
        color = Strata.colors.textSecondary,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .padding(horizontal = theme.list.horizontalPaddingDp.dp, vertical = 4.dp)
                .semantics { heading() }
                .testTag(SearchTestTags.RECENT_HEADER),
    )
}

@Composable
private fun ResultRow(
    app: VisibleApp,
    selected: Boolean,
    badgeStyle: BadgeStyle,
    onLaunch: (VisibleApp, LaunchOrigin?) -> Unit,
    onLongPress: (VisibleApp) -> Unit,
    actions: List<CustomAccessibilityAction>,
) {
    val view = LocalView.current
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    AppRow(
        label = app.displayLabel,
        secondary = app.secondaryLine,
        profileKind = app.profileKind,
        badge = app.badge,
        badgeStyle = badgeStyle,
        tag = if (app.hidden) stringResource(R.string.search_hidden_tag) else null,
        selected = selected,
        onClick = { onLaunch(app, coordinates[0]?.let { originOf(it, view) }) },
        onLongClick = { onLongPress(app) },
        actions = actions,
        icon = IconRequestKey(app.key, app.lastUpdateTime),
        modifier =
            Modifier
                .onGloballyPositioned { coordinates[0] = it }
                .testTag(SearchTestTags.row(app)),
    )
}

/** INTERACTION_SPEC §8 "No search results". */
@Composable
private fun NoResults(query: String) {
    val theme = Strata.theme
    Text(
        text = stringResource(R.string.search_no_results, query.trim()),
        style = Strata.typography.label,
        color = Strata.colors.textSecondary,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = theme.list.horizontalPaddingDp.dp, vertical = 12.dp)
                .testTag(SearchTestTags.NO_RESULTS),
    )
}

/** FR-SEARCH-07 hand-off row. */
@Composable
private fun StoreRow(onClick: () -> Unit) {
    val theme = Strata.theme
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = theme.list.horizontalPaddingDp.dp)
                .testTag(SearchTestTags.STORE_ROW),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = stringResource(R.string.search_store),
            style = Strata.typography.label,
            color = Strata.colors.accent,
        )
    }
}

/** Row bounds in screen pixels: `boundsInWindow()` plus the window's location (ANDROID_PLATFORM §4). */
private fun originOf(
    coordinates: LayoutCoordinates,
    view: View,
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

/** Predictive back fades the layer by up to this fraction (INTERACTION_SPEC §6). */
private const val BACK_FADE = 0.7f
