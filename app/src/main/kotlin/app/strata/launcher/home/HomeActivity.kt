package app.strata.launcher.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.strata.launcher.BuildConfig
import app.strata.launcher.appGraph
import app.strata.launcher.core.platformapi.system.SystemIntent
import app.strata.launcher.core.ui.LocalIconLoader
import app.strata.launcher.di.localeTags
import app.strata.launcher.feature.home.HomeAction
import app.strata.launcher.feature.home.HomeEffect
import app.strata.launcher.feature.home.HomeKeys
import app.strata.launcher.feature.home.HomeLayer
import app.strata.launcher.feature.home.HomeModal
import app.strata.launcher.feature.home.HomeRoute
import app.strata.launcher.feature.home.HomeViewModel
import app.strata.launcher.feature.home.rememberAppActions
import app.strata.launcher.feature.onboarding.OnboardingStubScreen
import app.strata.launcher.feature.search.SearchRoute
import app.strata.launcher.feature.search.SearchViewModel
import app.strata.launcher.platform.system.SystemIntentFactory
import app.strata.launcher.settings.SettingsActivity
import app.strata.launcher.theme.DebugThemeSwitch
import app.strata.launcher.theme.ThemeHost

/**
 * The HOME activity (ANDROID_PLATFORM §1, §22). Edge-to-edge over the wallpaper. Reads only
 * action and category of incoming intents; extras are ignored (ARCHITECTURE §13, T-SEC-03).
 */
class HomeActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels {
        viewModelFactory {
            initializer {
                val graph = appGraph
                HomeViewModel(
                    roleController = graph.roleController,
                    settings = graph.settingsRepository,
                    appList = graph.appList,
                    catalog = graph.appCatalog,
                    launcher = graph.appLauncher,
                    launchLog = graph.launchLog,
                    haptics = graph.haptics,
                    resetNotices = graph.resetNotices,
                    ioDispatcher = graph.dispatchers.io,
                    overrides = graph.overrides,
                    favorites = graph.favorites,
                    systemIntents = graph.systemIntents,
                    lockService = graph.lockService,
                    logger = graph.logger,
                    theme = graph.themeRepository,
                    themesAvailable = DebugThemeSwitch.AVAILABLE,
                )
            }
        }
    }

    private val searchViewModel: SearchViewModel by viewModels {
        viewModelFactory {
            initializer {
                val graph = appGraph
                SearchViewModel(
                    appList = graph.appList,
                    settings = graph.settingsRepository,
                    launchLog = graph.launchLog,
                    launcher = graph.appLauncher,
                    catalog = graph.appCatalog,
                    textFolding = graph.textFolding,
                    systemIntents = graph.systemIntents,
                    defaultDispatcher = graph.dispatchers.default,
                    ioDispatcher = graph.dispatchers.io,
                )
            }
        }
    }

    private var roleRequestStartedAt = 0L

    private val themeHost by lazy { ThemeHost(appGraph.themeRepository) }

    /** Debug builds: the internal theme switch (home menu → Themes) is open. */
    private var themeSwitchOpen by mutableStateOf(false)

    private val roleRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val elapsed = SystemClock.elapsedRealtime() - roleRequestStartedAt
            appGraph.logger.i(TAG, "role request result=${result.resultCode} after ${elapsed}ms")
            if (result.resultCode != Activity.RESULT_OK && elapsed < SILENT_REFUSAL_MS) {
                // "Don't ask again" refuses at once without UI (ANDROID_PLATFORM §2): go to settings instead of looping.
                launchFallback()
            }
            viewModel.onAction(HomeAction.RefreshRole)
        }

    /** Debug builds only: the search layer's Back/IME close decisions (booleans, no query), as `Strata/SearchClose`. */
    private val searchCloseLog: (String) -> Unit =
        if (BuildConfig.DEBUG) {
            { appGraph.logger.d("SearchClose", it) }
        } else {
            {}
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        appGraph.startTheme()
        appGraph.startCatalog()
        appGraph.attachWindow(window.decorView)
        val iconLoader = ProviderIconLoader(appGraph.iconProvider)
        themeHost.holdFirstFrame(window.decorView)
        setContent {
            themeHost.Content(window) {
                CompositionLocalProvider(LocalIconLoader provides iconLoader) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    // FR-LIFE-01 / T-PERF-01: fully drawn once the list (snapshot) is available.
                    ReportDrawnWhen { state.listReady }
                    WindowSettings(state.settings.hideStatusBar, state.settings.allowRotation)
                    val appActions = rememberAppActions(state.favoriteKeys, viewModel::onAction)
                    HomeRoute(
                        state = state,
                        effects = viewModel.effects,
                        onAction = viewModel::onAction,
                        onNavigate = ::navigate,
                        showSetDefaultButton = BuildConfig.DEBUG,
                        onSetDefault = ::requestDefaultHome,
                        timeTicks = appGraph.timeTicks.ticks,
                        searchLayer = { session ->
                            val searchState by searchViewModel.state.collectAsStateWithLifecycle()
                            SearchRoute(
                                state = searchState,
                                effects = searchViewModel.effects,
                                onAction = searchViewModel::onAction,
                                onClose = { viewModel.onAction(HomeAction.CloseSearch) },
                                seed = session.seed,
                                session = session.session,
                                onLongPress = { viewModel.onAction(HomeAction.OpenSheet(it)) },
                                rowActions = appActions,
                                closeLog = searchCloseLog,
                            )
                        },
                    )
                    if (state.showOnboarding) {
                        OnboardingStubScreen(onContinue = { viewModel.onAction(HomeAction.CompleteOnboarding) })
                    }
                    if (themeSwitchOpen) {
                        val theme by appGraph.themeRepository.state.collectAsStateWithLifecycle()
                        DebugThemeSwitch.Dialog(appGraph.themeRepository, theme.activeId) { themeSwitchOpen = false }
                    }
                }
            }
        }
    }

    /** FR-HOME-04 (status bar, home window only) and FR-HOME-08 (phone rotation lock). */
    @Composable
    private fun WindowSettings(
        hideStatusBar: Boolean,
        allowRotation: Boolean,
    ) {
        val smallestWidth = LocalConfiguration.current.smallestScreenWidthDp
        var statusBarTouched by remember { mutableStateOf(false) }
        LaunchedEffect(hideStatusBar) {
            // With the setting off from the start, leave the window's system-UI flags alone (below API 30 the
            // controller writes systemUiVisibility, including IMMERSIVE_STICKY, on every call).
            if (!hideStatusBar && !statusBarTouched) return@LaunchedEffect
            statusBarTouched = true
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hideStatusBar) {
                controller.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars())
            }
        }
        LaunchedEffect(allowRotation, smallestWidth) {
            requestedOrientation = HomeWindow.orientation(smallestWidth, allowRotation)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Drop half of the L1 icon cache when home goes to the background, all of it under real pressure.
        appGraph.iconProvider.trim(critical = level > ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
    }

    override fun onStart() {
        super.onStart()
        appGraph.refreshThemeInputs()
        // Callbacks are missed while not registered: reconcile on every start (ANDROID_PLATFORM §3.1).
        appGraph.appCatalog.requestReconcile()
    }

    override fun onStop() {
        themeHost.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        appGraph.attachWindow(null)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        appGraph.locales.value = localeTags(this)
        // API 31+: a new system palette arrives as a configuration change (dynamic colour, THEME_SYSTEM §5.2).
        appGraph.refreshThemeInputs()
    }

    private fun navigate(effect: HomeEffect) {
        val action =
            when (effect) {
                HomeEffect.OpenSettings -> {
                    Intent.ACTION_APPLICATION_PREFERENCES
                }

                HomeEffect.OpenHiddenApps -> {
                    SettingsActivity.ACTION_HIDDEN_APPS
                }

                HomeEffect.LockHint -> {
                    appGraph.systemIntents.accessibilitySettings()
                    return
                }

                HomeEffect.OpenThemes -> {
                    themeSwitchOpen = true
                    return
                }

                else -> {
                    return
                }
            }
        startActivity(Intent(action).setClass(this, SettingsActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAction(HomeAction.RefreshRole)
    }

    /**
     * Fallback for keys no focused composable consumed (nothing focused on home): a printable key on
     * home or list opens search with that character (FR-SEARCH-01, INTERACTION_SPEC §5).
     */
    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val current = viewModel.state.value
        val layer = current.layer
        val idle = current.modal == HomeModal.None && layer != HomeLayer.FAV_EDIT
        if (layer != HomeLayer.SEARCH && idle && !current.showOnboarding) {
            val text =
                HomeKeys.printable(
                    event.unicodeChar,
                    event.isCtrlPressed,
                    event.isAltPressed,
                    event.isMetaPressed,
                )
            if (text != null) {
                viewModel.onAction(HomeAction.OpenSearch(text))
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (HomeIntents.isHomePress(intent.action, intent.categories)) {
            viewModel.onAction(HomeAction.HomePressed)
        }
    }

    /** Debug "Set default" (IMPLEMENTATION_ROADMAP M0): role request on 29+, settings chain otherwise. */
    private fun requestDefaultHome() {
        val roles = appGraph.roleController
        when (val first = roles.requestIntent()) {
            SystemIntent.RequestHomeRole -> {
                val intent = SystemIntentFactory.create(this, first)
                if (intent == null) {
                    launchFallback()
                    return
                }
                roleRequestStartedAt = SystemClock.elapsedRealtime()
                try {
                    roleRequest.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    appGraph.logger.w(TAG, "role request activity missing", e)
                    launchFallback()
                }
            }

            else -> {
                launchFallback()
            }
        }
    }

    private fun launchFallback() {
        for (target in appGraph.roleController.fallbackIntents()) {
            val intent = SystemIntentFactory.create(this, target) ?: continue
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                appGraph.logger.w(TAG, "no activity for $target", e)
            }
        }
    }

    private companion object {
        const val TAG = "Home"
        const val SILENT_REFUSAL_MS = 2_000L
    }
}
