package app.strata.launcher.feature.home

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.theme.model.IndexGlyphs
import app.strata.launcher.core.theme.model.IndexSide
import app.strata.launcher.core.theme.model.IndexVisibility
import app.strata.launcher.core.theme.model.SearchPosition
import app.strata.launcher.core.theme.model.VerticalPosition
import app.strata.launcher.core.theme.store.ThemeBanner
import app.strata.launcher.core.theme.store.ThemeBannerKind
import app.strata.launcher.core.ui.AlphabetIndex
import app.strata.launcher.core.ui.IndexExclusion
import app.strata.launcher.core.ui.SearchPillFrame
import app.strata.launcher.core.ui.Strata
import app.strata.launcher.core.ui.backdropFade
import app.strata.launcher.core.ui.strataBackdrop
import app.strata.launcher.feature.home.list.AppListScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import app.strata.launcher.core.ui.R as UiR

object HomeTestTags {
    const val ROOT = "home_root"
    const val APP_MODE_BANNER = "home_app_mode_banner"
    const val SET_DEFAULT_BUTTON = "home_set_default_button"
    const val ALL_APPS = "home_all_apps"
    const val RESET_BANNER = "home_reset_banner"
    const val THEME_BANNER = "home_theme_banner"
    const val THEME_BANNER_DETAILS = "home_theme_banner_details"
    const val CLOCK = "home_clock"
    const val DATE = "home_date"
    const val FAVORITES = "home_favorites"
    const val ADD_FAVORITES = "home_add_favorites"
    const val PILL = "home_search_pill"
    const val PILL_MENU = "home_search_pill_menu"
    const val HOME_INDEX = "home_index"

    fun favorite(app: VisibleApp) = "home_favorite_${app.key.component}_${app.key.userSerial}"
}

/**
 * Home activity content: HOME_REST, the LIST and FAVEDIT layers, the search slot, one modal, banners, snackbars
 * and Back. [onNavigate] receives effects that need the activity.
 */
@Composable
fun HomeRoute(
    state: HomeUiState,
    effects: Flow<HomeEffect>,
    onAction: (HomeAction) -> Unit,
    onNavigate: (HomeEffect) -> Unit,
    showSetDefaultButton: Boolean,
    onSetDefault: () -> Unit,
    modifier: Modifier = Modifier,
    /** The search layer, supplied by `:app` (features never import each other, ARCHITECTURE §2.1). */
    searchLayer: @Composable (SearchSession) -> Unit = {},
    /** Minute ticks for the header clock (ARCHITECTURE §3 `Clock.ticks`). */
    timeTicks: Flow<Unit> = flowOf(Unit),
) {
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val favoritesScroll = rememberScrollState()
    val resources = LocalResources.current
    val context = LocalContext.current
    val currentOnAction by rememberUpdatedState(onAction)
    val currentOnNavigate by rememberUpdatedState(onNavigate)
    var letterGrid by remember { mutableStateOf(false) }
    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is HomeEffect.LaunchFailed -> {
                    launch {
                        val result =
                            snackbar.showSnackbar(
                                message = resources.getString(R.string.launch_failed, effect.label),
                                actionLabel = resources.getString(R.string.launch_failed_app_info),
                                duration = SnackbarDuration.Long,
                            )
                        if (result == SnackbarResult.ActionPerformed) {
                            currentOnAction(HomeAction.OpenAppInfo(effect.key, null))
                        }
                    }
                }

                HomeEffect.ReturnToRest -> {
                    snackbar.currentSnackbarData?.dismiss()
                    letterGrid = false
                    listState.scrollToItem(0)
                    favoritesScroll.scrollTo(0)
                }

                HomeEffect.NoHandler -> {
                    Toast.makeText(context, R.string.no_handler, Toast.LENGTH_SHORT).show()
                }

                HomeEffect.WriteFailed -> {
                    Toast.makeText(context, R.string.write_failed, Toast.LENGTH_SHORT).show()
                }

                is HomeEffect.Hidden -> {
                    launch {
                        val result =
                            snackbar.showTimed(
                                resources.getString(R.string.hidden_snackbar),
                                resources.getString(R.string.undo),
                                UNDO_MS,
                            )
                        if (result == SnackbarResult.ActionPerformed) {
                            currentOnAction(HomeAction.SetHidden(effect.key, hidden = false))
                        }
                    }
                }

                HomeEffect.AppRemoved -> {
                    launch { snackbar.showSnackbar(resources.getString(R.string.app_removed)) }
                }

                is HomeEffect.NotYetAvailable -> {
                    val text =
                        when (effect.feature) {
                            LaterFeature.WIDGETS -> R.string.later_widgets
                            LaterFeature.THEMES -> R.string.later_themes
                            LaterFeature.ICONS -> R.string.later_icons
                        }
                    launch { snackbar.showSnackbar(resources.getString(text)) }
                }

                HomeEffect.LockHint -> {
                    launch {
                        val result =
                            snackbar.showSnackbar(
                                message = resources.getString(R.string.lock_hint),
                                actionLabel = resources.getString(R.string.lock_hint_action),
                                duration = SnackbarDuration.Long,
                            )
                        if (result == SnackbarResult.ActionPerformed) currentOnNavigate(HomeEffect.LockHint)
                    }
                }

                else -> {
                    currentOnNavigate(effect)
                }
            }
        }
    }
    val noModal = state.modal == HomeModal.None
    BackHandler(enabled = state.layer == HomeLayer.LIST && noModal) { onAction(HomeAction.CloseList) }
    BackHandler(enabled = state.layer == HomeLayer.FAV_EDIT && noModal) { onAction(HomeAction.DoneEditing) }

    val search = state.search
    val listShown = state.layer == HomeLayer.LIST || search?.origin == HomeLayer.LIST
    val resumed =
        LocalLifecycleOwner.current.lifecycle
            .currentStateAsState()
            .value
            .isAtLeast(Lifecycle.State.RESUMED)
    val exclusion =
        when {
            !resumed -> IndexExclusion.NONE
            state.settings.keepBackGestureOnIndexSide -> IndexExclusion.AROUND_TOUCH
            else -> IndexExclusion.FULL
        }
    val actions = rememberAppActions(state.favoriteKeys, onAction)
    // Test tags double as resource ids, so UiAutomator and Macrobenchmark can find nodes (T-PERF-03/04/10).
    Box(
        modifier =
            modifier
                .fillMaxSize()
                // Home / list backdrop E(W) (VISUAL_SPEC §6), once under every layer.
                .strataBackdrop(Strata.theme, Strata.colors, Strata.backdropFade)
                .semantics { testTagsAsResourceId = true }
                .onKeyEvent { event -> handleHomeKey(event, state, onAction) },
    ) {
        val underSearch = search != null
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    // INTERACTION_SPEC §7: home (or list) content fades to 0.3 α under search.
                    .alpha(if (underSearch) UNDER_SEARCH_ALPHA else 1f)
                    .then(if (underSearch || !noModal) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            HomeScreen(
                // The list layer replaces the rest page (INTERACTION_SPEC §3); it stays composed but unseen.
                modifier = if (listShown) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier,
                state = state,
                showSetDefaultButton = showSetDefaultButton,
                onSetDefault = onSetDefault,
                onAction = onAction,
                appActions = actions,
                favoritesScroll = favoritesScroll,
                timeTicks = timeTicks,
                indexExclusion = if (listShown || state.layer == HomeLayer.FAV_EDIT) IndexExclusion.NONE else exclusion,
            )
            val list = state.list
            if (listShown && list != null) {
                AppListScreen(
                    list = list,
                    twoColumnSetting = state.listTwoColumn,
                    badgeStyle = state.badgeStyle,
                    onLaunch = { app, origin -> onAction(HomeAction.Launch(app, origin)) },
                    onIndexScrub = { first -> onAction(HomeAction.IndexScrub(first)) },
                    onOpenSettings = { onAction(HomeAction.OpenSettings) },
                    onOpenHiddenApps = { onAction(HomeAction.OpenHiddenApps) },
                    onOpenSearch = { onAction(HomeAction.OpenSearch()) },
                    listState = listState,
                    onLongPress = { onAction(HomeAction.OpenSheet(it)) },
                    rowActions = actions,
                    jump = state.listJump,
                    onJumpConsumed = { onAction(HomeAction.JumpConsumed(it.seq)) },
                    onJumpToLetterMenu = { letterGrid = true },
                    indexExclusion = if (search == null) exclusion else IndexExclusion.NONE,
                )
            }
            if (state.layer == HomeLayer.FAV_EDIT) {
                FavoritesEditScreen(state = state, onAction = onAction)
            }
        }
        if (search != null) searchLayer(search)
        HomeModals(state = state, onAction = onAction)
        val list = state.list
        if (letterGrid && list != null && listShown) {
            LetterGrid(
                letters = list.index,
                onPick = {
                    letterGrid = false
                    onAction(HomeAction.JumpToLetter(it))
                },
                onDismiss = { letterGrid = false },
            )
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
    }
}

/** Snackbar for [millis] (J-05: 5 s), returning whether the action was taken. */
private suspend fun SnackbarHostState.showTimed(
    message: String,
    action: String,
    millis: Long,
): SnackbarResult =
    kotlinx.coroutines.coroutineScope {
        val timer =
            launch {
                delay(millis)
                currentSnackbarData?.dismiss()
            }
        showSnackbar(message = message, actionLabel = action, duration = SnackbarDuration.Indefinite)
            .also { timer.cancel() }
    }

/**
 * Keyboard model of INTERACTION_SPEC §5 on home and list: a printable key opens search with that character,
 * Tab opens the list from rest, Esc closes the list, Ctrl+, opens settings.
 */
private fun handleHomeKey(
    event: KeyEvent,
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
): Boolean {
    val layer = state.layer
    if (event.type != KeyEventType.KeyDown || layer == HomeLayer.SEARCH || layer == HomeLayer.FAV_EDIT) return false
    if (state.modal != HomeModal.None) return false
    when {
        event.isCtrlPressed && event.key == Key.Comma -> {
            onAction(HomeAction.OpenSettings)
        }

        event.key == Key.Tab && layer == HomeLayer.REST -> {
            onAction(HomeAction.OpenList)
        }

        event.key == Key.Escape && layer == HomeLayer.LIST -> {
            onAction(HomeAction.CloseList)
        }

        else -> {
            val text =
                HomeKeys.printable(event.utf16CodePoint, event.isCtrlPressed, event.isAltPressed, event.isMetaPressed)
                    ?: return false
            onAction(HomeAction.OpenSearch(text))
        }
    }
    return true
}

/** Menu key / Shift+F10 on a focused app opens its sheet (INTERACTION_SPEC §5). */
internal fun Modifier.sheetKey(
    app: VisibleApp,
    onAction: (HomeAction) -> Unit,
): Modifier =
    onKeyEvent { event ->
        val menu = event.key == Key.Menu || (event.isShiftPressed && event.key == Key.F10)
        if (event.type == KeyEventType.KeyDown && menu) {
            onAction(HomeAction.OpenSheet(app))
            true
        } else {
            false
        }
    }

/** INTERACTION_SPEC §7 "HOME → SEARCH": home content fades to 0.3 α. */
private const val UNDER_SEARCH_ALPHA = 0.3f

/** J-05: the hide undo snackbar stays 5 s. */
private const val UNDO_MS = 5_000L

/**
 * HOME_REST (FR-HOME-02, VISUAL_SPEC §2): status inset, header, flexible space, favourites + "All apps", search
 * pill, nav inset; the alphabet index on the theme side when its visibility is `always`. Empty space takes
 * long-press (home menu) and, when on, double-tap (lock); a swipe up anywhere opens search.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    showSetDefaultButton: Boolean,
    onSetDefault: () -> Unit,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
    appActions: (VisibleApp) -> List<CustomAccessibilityAction> = { emptyList() },
    favoritesScroll: ScrollState = rememberScrollState(),
    timeTicks: Flow<Unit> = flowOf(Unit),
    indexExclusion: IndexExclusion = IndexExclusion.NONE,
) {
    val theme = Strata.theme
    val rootActions = homeRootActions(state, onAction)
    val doubleTap: (() -> Unit)? = if (state.lockGestureOn) ({ onAction(HomeAction.DoubleTapEmpty) }) else null
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .swipeUp { onAction(HomeAction.OpenSearch()) }
                .semantics { customActions = rootActions }
                .testTag(HomeTestTags.ROOT),
    ) {
        // Background layer: only touches that no child took (empty home) arrive here.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .emptySpaceGestures(onLongPress = { onAction(HomeAction.OpenHomeMenu) }, onDoubleTap = doubleTap),
        )
        val list = state.list
        val showIndex =
            theme.alphabetIndex.visibility == IndexVisibility.ALWAYS && list != null && list.index.isNotEmpty()
        val atEnd = theme.alphabetIndex.side == IndexSide.END
        val indexReserve = if (showIndex) INDEX_RESERVE else 0.dp
        val landscape = maxWidth > maxHeight
        val sidePadding = if (maxWidth >= MEDIUM_WIDTH) MEDIUM_SIDE_PADDING else 0.dp
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(
                        start = sidePadding + if (!atEnd) indexReserve else 0.dp,
                        end = sidePadding + if (atEnd) indexReserve else 0.dp,
                    ),
        ) {
            val themeBanner = state.themeBanner
            if (state.showAppModeBanner || state.showResetBanner || themeBanner != null) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.showAppModeBanner) {
                        AppModeBanner(showSetDefaultButton = showSetDefaultButton, onSetDefault = onSetDefault)
                    }
                    if (state.showResetBanner) ResetBanner { onAction(HomeAction.DismissResetBanner) }
                    if (themeBanner != null) ThemeBannerCard(themeBanner) { onAction(HomeAction.DismissThemeBanner) }
                }
            }
            val header: @Composable (Modifier) -> Unit = { m ->
                HomeHeader(
                    ticks = timeTicks,
                    onClock = { onAction(HomeAction.OpenClock) },
                    onDate = { onAction(HomeAction.OpenCalendar) },
                    modifier = m.padding(top = HEADER_TOP, start = 16.dp, end = 16.dp),
                )
            }
            val pill: @Composable () -> Unit = {
                if (theme.search.position != SearchPosition.HIDDEN) {
                    HomePill(
                        onOpen = { onAction(HomeAction.OpenSearch()) },
                        onMenu = { onAction(HomeAction.OpenHomeMenu) },
                    )
                }
            }
            if (landscape) {
                // VISUAL_SPEC §8.1: header start 40 %, favourites and pill end 60 %.
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(HEADER_WEIGHT).fillMaxHeight()) { header(Modifier) }
                    Column(modifier = Modifier.weight(1f - HEADER_WEIGHT).fillMaxHeight()) {
                        if (theme.search.position == SearchPosition.TOP) pill()
                        FavoritesRegion(state, onAction, appActions, favoritesScroll, Modifier.weight(1f))
                        if (theme.search.position == SearchPosition.BOTTOM) pill()
                    }
                }
            } else {
                header(Modifier)
                if (theme.search.position == SearchPosition.TOP) pill()
                FavoritesRegion(state, onAction, appActions, favoritesScroll, Modifier.weight(1f))
                if (theme.search.position == SearchPosition.BOTTOM) pill()
            }
        }
        if (showIndex && list != null) {
            AlphabetIndex(
                letters = list.index,
                atEnd = atEnd,
                dots = theme.alphabetIndex.glyphs == IndexGlyphs.DOTS,
                showBubble = false,
                exclusion = indexExclusion,
                onSelect = { letter, first -> onAction(HomeAction.HomeIndexScrub(letter, first)) },
                modifier =
                    Modifier
                        .align(if (atEnd) Alignment.CenterEnd else Alignment.CenterStart)
                        .safeDrawingPadding()
                        .padding(bottom = INDEX_BOTTOM_GAP)
                        .testTag(HomeTestTags.HOME_INDEX),
            )
        }
    }
}

/**
 * The flexible space plus the favourites block (FR-FAV-01…03): the block sits at its `verticalPosition` and scrolls
 * internally only when its content is taller than the space (large fonts, landscape). A swipe up that starts inside
 * a scrolling block scrolls it; once it is at its end, the leftover swipe opens search (INTERACTION_SPEC §4.2 rule 4).
 */
@Composable
private fun FavoritesRegion(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    appActions: (VisibleApp) -> List<CustomAccessibilityAction>,
    scroll: ScrollState,
    modifier: Modifier,
) {
    val theme = Strata.theme
    val density = LocalDensity.current
    val swipePx = with(density) { HomeGestures.SWIPE_MIN_DP.dp.toPx() }
    val flingPx = with(density) { HomeGestures.FLING_MIN_DP_PER_S.dp.toPx() }
    val currentOnAction by rememberUpdatedState(onAction)
    val connection =
        remember(swipePx, flingPx) {
            object : NestedScrollConnection {
                var leftover = 0f
                var fired = false

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // Finger moving up at the end of the block: the content cannot scroll further.
                    if (available.y < 0f && !fired) {
                        leftover += -available.y
                        if (leftover >= swipePx) {
                            fired = true
                            currentOnAction(HomeAction.OpenSearch())
                        }
                    }
                    return Offset.Zero
                }

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity {
                    if (!fired && available.y <= -flingPx) currentOnAction(HomeAction.OpenSearch())
                    leftover = 0f
                    fired = false
                    return Velocity.Zero
                }
            }
        }
    val contentAlignment =
        when (theme.favorites.verticalPosition) {
            VerticalPosition.TOP -> Alignment.TopCenter
            VerticalPosition.CENTER -> Alignment.Center
            VerticalPosition.BOTTOM -> Alignment.BottomCenter
        }
    Box(
        modifier = modifier.fillMaxWidth().padding(top = FLEX_MIN).nestedScroll(connection),
        contentAlignment = contentAlignment,
    ) {
        FavoritesBlock(
            state = state,
            onAction = onAction,
            appActions = appActions,
            modifier =
                Modifier
                    .widthIn(max = FAVORITES_MAX_WIDTH)
                    .verticalScroll(scroll, enabled = scroll.maxValue > 0, overscrollEffect = null),
        )
    }
}

@Composable
private fun FavoritesBlock(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    appActions: (VisibleApp) -> List<CustomAccessibilityAction>,
    modifier: Modifier,
) {
    val theme = Strata.theme
    val favorites = state.favorites
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { collectionInfo = CollectionInfo(rowCount = favorites.size, columnCount = 1) }
                .testTag(HomeTestTags.FAVORITES),
        verticalArrangement = Arrangement.spacedBy(theme.favorites.spacingDp.dp),
    ) {
        if (favorites.isEmpty() && state.list != null) {
            // INTERACTION_SPEC §8 "No favourites".
            TextButton(
                onClick = { onAction(HomeAction.EditFavorites) },
                modifier = Modifier.padding(horizontal = 8.dp).testTag(HomeTestTags.ADD_FAVORITES),
            ) {
                Text(text = stringResource(R.string.add_favorites), style = Strata.typography.label)
            }
        }
        favorites.forEach { app ->
            FavoriteRow(
                app = app,
                badgeStyle = state.badgeStyle,
                onLaunch = { origin -> onAction(HomeAction.Launch(app, origin)) },
                onLongPress = { onAction(HomeAction.OpenSheet(app)) },
                actions = appActions(app),
                modifier = Modifier.sheetKey(app, onAction).testTag(HomeTestTags.favorite(app)),
            )
        }
        AllAppsRow(onOpen = { onAction(HomeAction.OpenList) })
    }
}

/** VISUAL_SPEC §9.2: "All apps ›" ends the block, favourite style in `textSecondary`. */
@Composable
private fun AllAppsRow(onOpen: () -> Unit) {
    val theme = Strata.theme
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(horizontal = theme.list.horizontalPaddingDp.dp)
                .testTag(HomeTestTags.ALL_APPS),
        contentAlignment = favoriteAlignment(),
    ) {
        Text(
            text = stringResource(R.string.home_all_apps),
            style = favoriteTextStyle(),
            color = Strata.colors.textSecondary,
        )
    }
}

/** VISUAL_SPEC §9.1: pill with the search glyph, hint and an overflow button that opens the home menu. */
@Composable
private fun HomePill(
    onOpen: () -> Unit,
    onMenu: () -> Unit,
) {
    val theme = Strata.theme
    val colors = Strata.colors
    val menuDescription = stringResource(R.string.home_menu)
    SearchPillFrame(
        onClick = onOpen,
        modifier =
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = PILL_BOTTOM)
                .testTag(HomeTestTags.PILL),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (theme.search.showHint) {
                Text(
                    text = stringResource(UiR.string.search_hint),
                    style = Strata.typography.label,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clickable(role = Role.Button, onClick = onMenu)
                    .semantics { contentDescription = menuDescription }
                    .testTag(HomeTestTags.PILL_MENU),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(20.dp)) {
                val r = 2.dp.toPx()
                for (i in -1..1) {
                    drawCircle(
                        colors.textSecondary,
                        r,
                        Offset(
                            size.width / 2f,
                            size.height / 2f + i * 6.dp.toPx(),
                        ),
                    )
                }
            }
        }
    }
}

/** INTERACTION_SPEC §9: home-root custom actions (Search, All apps, Widgets, Themes, Settings, Lock screen). */
@Composable
private fun homeRootActions(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
): List<CustomAccessibilityAction> {
    val search = stringResource(R.string.action_search)
    val allApps = stringResource(R.string.action_all_apps)
    val widgets = stringResource(R.string.menu_widgets)
    val themes = stringResource(R.string.menu_themes)
    val settings = stringResource(R.string.menu_settings)
    val edit = stringResource(R.string.menu_edit_favorites)
    val lock = stringResource(R.string.action_lock_screen)

    fun action(
        label: String,
        a: HomeAction,
    ) = CustomAccessibilityAction(label) {
        onAction(a)
        true
    }
    return listOfNotNull(
        action(search, HomeAction.OpenSearch()),
        action(allApps, HomeAction.OpenList),
        action(widgets, HomeAction.MenuWidgets),
        action(themes, HomeAction.MenuThemes),
        action(settings, HomeAction.OpenSettings),
        action(edit, HomeAction.EditFavorites),
        if (state.lockGestureOn) action(lock, HomeAction.LockScreen) else null,
    )
}

@Composable
private fun AppModeBanner(
    showSetDefaultButton: Boolean,
    onSetDefault: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag(HomeTestTags.APP_MODE_BANNER),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.home_banner_not_default),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (showSetDefaultButton) {
                Button(onClick = onSetDefault, modifier = Modifier.testTag(HomeTestTags.SET_DEFAULT_BUTTON)) {
                    Text(text = stringResource(R.string.home_banner_set_default))
                }
            }
        }
    }
}

@Composable
private fun ResetBanner(onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag(HomeTestTags.RESET_BANNER),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.reset_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(text = stringResource(R.string.reset_banner_dismiss))
            }
        }
    }
}

/** THEME_SYSTEM §12 step 2 (and safe mode, ARCHITECTURE §10). Non-blocking; [Choose theme] arrives with M5. */
@Composable
private fun ThemeBannerCard(
    banner: ThemeBanner,
    onDismiss: () -> Unit,
) {
    var details by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag(HomeTestTags.THEME_BANNER),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text =
                    stringResource(
                        if (banner.kind ==
                            ThemeBannerKind.SAFE_MODE
                        ) {
                            R.string.theme_banner_safe_mode
                        } else {
                            R.string.theme_banner
                        },
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (details) {
                Text(
                    text = banner.details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.testTag(HomeTestTags.THEME_BANNER_DETAILS),
                )
            }
            Row(modifier = Modifier.align(Alignment.End)) {
                if (!details) {
                    TextButton(
                        onClick = { details = true },
                    ) { Text(text = stringResource(R.string.theme_banner_details)) }
                }
                TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.reset_banner_dismiss)) }
            }
        }
    }
}

private val INDEX_RESERVE = 48.dp
private val INDEX_BOTTOM_GAP = 24.dp
private val HEADER_TOP = 24.dp
private val FLEX_MIN = 16.dp
private val PILL_BOTTOM = 12.dp
private val MEDIUM_WIDTH = 600.dp
private val MEDIUM_SIDE_PADDING = 32.dp
private val FAVORITES_MAX_WIDTH = 480.dp
private const val HEADER_WEIGHT = 0.4f
