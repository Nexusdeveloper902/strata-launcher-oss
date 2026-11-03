package app.strata.launcher.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import app.strata.launcher.core.ui.Strata
import app.strata.launcher.feature.home.list.AppListScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

object HomeTestTags {
    const val PLACEHOLDER = "home_placeholder"
    const val APP_MODE_BANNER = "home_app_mode_banner"
    const val SET_DEFAULT_BUTTON = "home_set_default_button"
    const val ALL_APPS = "home_all_apps"
    const val RESET_BANNER = "home_reset_banner"
}

/**
 * Home activity content: HOME_REST (placeholder until the favourites of M3, plus "All apps") and the
 * LIST layer, banners, the launch-failure snackbar and Back. [onNavigate] receives effects that need
 * the activity.
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
) {
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val resources = LocalResources.current
    val currentOnAction by rememberUpdatedState(onAction)
    val currentOnNavigate by rememberUpdatedState(onNavigate)
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
                    listState.scrollToItem(0)
                }

                else -> {
                    currentOnNavigate(effect)
                }
            }
        }
    }
    BackHandler(enabled = state.layer == HomeLayer.LIST) { onAction(HomeAction.CloseList) }

    val search = state.search
    val listShown = state.layer == HomeLayer.LIST || search?.origin == HomeLayer.LIST
    // Test tags double as resource ids, so UiAutomator and Macrobenchmark can find nodes (T-PERF-03/04/10).
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .onKeyEvent { event -> handleHomeKey(event, state.layer, onAction) },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    // INTERACTION_SPEC §7: home (or list) content fades to 0.3 α under search.
                    .alpha(if (search != null) UNDER_SEARCH_ALPHA else 1f)
                    .then(if (search != null) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            HomeLayers(state, listShown, listState, showSetDefaultButton, onSetDefault, onAction)
        }
        if (search != null) searchLayer(search)
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
    }
}

/**
 * Keyboard model of INTERACTION_SPEC §5 on home and list: a printable key opens search with that
 * character, Tab opens the list from rest, Esc closes the list, Ctrl+, opens settings.
 */
private fun handleHomeKey(
    event: KeyEvent,
    layer: HomeLayer,
    onAction: (HomeAction) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown || layer == HomeLayer.SEARCH) return false
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

@Composable
private fun HomeLayers(
    state: HomeUiState,
    listShown: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    showSetDefaultButton: Boolean,
    onSetDefault: () -> Unit,
    onAction: (HomeAction) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        HomeScreen(
            state = state,
            showSetDefaultButton = showSetDefaultButton,
            onSetDefault = onSetDefault,
            onOpenList = { onAction(HomeAction.OpenList) },
            onDismissReset = { onAction(HomeAction.DismissResetBanner) },
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
            )
        }
    }
}

/** INTERACTION_SPEC §7 "HOME → SEARCH": home content fades to 0.3 α. */
private const val UNDER_SEARCH_ALPHA = 0.3f

/**
 * HOME_REST: placeholder home (T-HOME-01) over the wallpaper, the APP_MODE banner stub (T-HOME-02),
 * the reset banner (FR-LIFE-04) and the "All apps" row that opens the list.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    showSetDefaultButton: Boolean,
    onSetDefault: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenList: () -> Unit = {},
    onDismissReset: () -> Unit = {},
) {
    val colors = Strata.colors
    Box(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.showAppModeBanner) {
                AppModeBanner(showSetDefaultButton = showSetDefaultButton, onSetDefault = onSetDefault)
            }
            if (state.showResetBanner) ResetBanner(onDismissReset)
        }
        Text(
            text = stringResource(R.string.home_placeholder),
            style = MaterialTheme.typography.headlineMedium,
            color = colors.textPrimary,
            modifier = Modifier.align(Alignment.Center).testTag(HomeTestTags.PLACEHOLDER),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onOpenList)
                    .padding(horizontal = 16.dp)
                    .testTag(HomeTestTags.ALL_APPS),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = stringResource(R.string.home_all_apps),
                style = Strata.typography.label,
                color = colors.textSecondary,
            )
        }
    }
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
