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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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

    // Test tags double as resource ids, so UiAutomator and Macrobenchmark can find nodes (T-PERF-03/04/10).
    Box(modifier = modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        HomeScreen(
            state = state,
            showSetDefaultButton = showSetDefaultButton,
            onSetDefault = onSetDefault,
            onOpenList = { onAction(HomeAction.OpenList) },
            onDismissReset = { onAction(HomeAction.DismissResetBanner) },
        )
        val list = state.list
        if (state.layer == HomeLayer.LIST && list != null) {
            AppListScreen(
                list = list,
                twoColumnSetting = state.listTwoColumn,
                badgeStyle = state.badgeStyle,
                onLaunch = { app, origin -> onAction(HomeAction.Launch(app, origin)) },
                onIndexScrub = { first -> onAction(HomeAction.IndexScrub(first)) },
                onOpenSettings = { onAction(HomeAction.OpenSettings) },
                onOpenHiddenApps = { onAction(HomeAction.OpenHiddenApps) },
                listState = listState,
            )
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
    }
}

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
