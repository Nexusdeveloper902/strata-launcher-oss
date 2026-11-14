package app.strata.launcher.home

import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.strata.launcher.BuildConfig
import app.strata.launcher.appGraph
import app.strata.launcher.core.common.trace.TraceSections
import app.strata.launcher.core.ui.LocalIconLoader
import app.strata.launcher.di.localeTags
import app.strata.launcher.feature.home.HomeAction
import app.strata.launcher.feature.home.HomeEffect
import app.strata.launcher.feature.home.HomeKeys
import app.strata.launcher.feature.home.HomeLayer
import app.strata.launcher.feature.home.HomeModal
import app.strata.launcher.feature.home.HomeRoute
import app.strata.launcher.feature.home.HomeViewModel
import app.strata.launcher.feature.home.HomeWidgetSlot
import app.strata.launcher.feature.home.rememberAppActions
import app.strata.launcher.feature.onboarding.OnboardingAction
import app.strata.launcher.feature.onboarding.OnboardingRoute
import app.strata.launcher.feature.onboarding.OnboardingViewModel
import app.strata.launcher.feature.onboarding.SetDefaultDialogs
import app.strata.launcher.feature.onboarding.SetDefaultViewModel
import app.strata.launcher.feature.search.SearchAction
import app.strata.launcher.feature.search.SearchRoute
import app.strata.launcher.feature.search.SearchViewModel
import app.strata.launcher.feature.widgets.WidgetArea
import app.strata.launcher.feature.widgets.WidgetAreaEffect
import app.strata.launcher.feature.widgets.WidgetAreaViewModel
import app.strata.launcher.feature.widgets.WidgetMenu
import app.strata.launcher.onboarding.SystemScreenLauncher
import app.strata.launcher.settings.SettingsActivity
import app.strata.launcher.startup.AndroidTracer
import app.strata.launcher.theme.IconChooserActivity
import app.strata.launcher.theme.ThemeHost
import app.strata.launcher.theme.ThemesActivity
import app.strata.launcher.widget.HostedWidget
import app.strata.launcher.widget.WidgetPickerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
                    themesAvailable = true,
                    iconPacks = graph.iconProvider.iconPacks,
                    profiles = graph.profiles,
                    shortcuts = graph.shortcuts,
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
                    contacts = graph.contacts,
                )
            }
        }
    }

    /** D-008: the first-run flow as home's first layer while `onboarding_done` is false (FR-ONBOARD-01). */
    private val onboardingViewModel: OnboardingViewModel by viewModels {
        viewModelFactory {
            initializer {
                val graph = appGraph
                OnboardingViewModel(
                    appList = graph.appList,
                    favorites = graph.favorites,
                    settings = graph.settingsRepository,
                    roles = graph.roleController,
                    io = graph.dispatchers.io,
                    logger = graph.logger,
                    handle = createSavedStateHandle(),
                )
            }
        }
    }

    /** The APP_MODE banner's "Set as default" / "Set again" (FR-ONBOARD-02/04). */
    private val setDefaultViewModel: SetDefaultViewModel by viewModels {
        viewModelFactory {
            initializer {
                val graph = appGraph
                SetDefaultViewModel(graph.roleController, graph.dispatchers.io, graph.logger)
            }
        }
    }

    /** The header widget area (FR-WIDGET-01…04); safe mode suppresses it (ARCHITECTURE §10). */
    private val widgetArea: WidgetAreaViewModel by viewModels {
        viewModelFactory {
            initializer {
                val graph = appGraph
                WidgetAreaViewModel(
                    host = graph.widgetHost,
                    repository = graph.widgets,
                    logger = graph.logger,
                    // ARCHITECTURE §10: no widget before the boot result is known, none in safe mode.
                    suppressed = graph.themeRepository.state.map { !it.loaded || it.safeMode },
                )
            }
        }
    }

    private val systemScreens = SystemScreenLauncher(this) { appGraph.logger }

    private val themeHost by lazy { ThemeHost(appGraph.themeRepository) }

    /** Debug builds only: the search layer's Back/IME close decisions (booleans, no query), as `Strata/SearchClose`. */
    private val searchCloseLog: (String) -> Unit =
        if (BuildConfig.DEBUG) {
            { appGraph.logger.d("SearchClose", it) }
        } else {
            {}
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidTracer.begin(TraceSections.HOME_CREATE)
        try {
            enableEdgeToEdge()
            super.onCreate(savedInstanceState)
            // ARCHITECTURE §10 step 3: housekeeping deferred at start runs once the list is on screen (reportFullyDrawn).
            fullyDrawnReporter.addOnReportDrawnListener { appGraph.firstFrameDrawn() }
            appGraph.startTheme()
            appGraph.startCatalog()
            appGraph.attachWindow(window.decorView)
            val iconLoader = ProviderIconLoader(appGraph.iconProvider)
            systemScreens.bind(onboardingViewModel.setDefault)
            systemScreens.bind(setDefaultViewModel.flow)
            themeHost.holdFirstFrame(window.decorView)
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    widgetArea.effects.collect { effect ->
                        if (effect is WidgetAreaEffect.OpenPicker) {
                            startActivity(WidgetPickerActivity.intent(this@HomeActivity, effect.readd))
                        }
                    }
                }
            }
            setContent {
                themeHost.Content(window) {
                    CompositionLocalProvider(LocalIconLoader provides iconLoader) {
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        // FR-LIFE-01 / T-PERF-01: fully drawn once the list (snapshot) is available.
                        ReportDrawnWhen { state.listReady }
                        WindowSettings(state.settings.hideStatusBar, state.settings.allowRotation)
                        HideStrayIme(state.imeAllowed)
                        val appActions = rememberAppActions(state.favoriteKeys, viewModel::onAction)
                        HomeRoute(
                            state = state,
                            effects = viewModel.effects,
                            onAction = viewModel::onAction,
                            onNavigate = ::navigate,
                            showSetDefaultButton = true,
                            onSetDefault = { setDefaultViewModel.flow.setAsDefault() },
                            timeTicks = appGraph.timeTicks.ticks,
                            widgetSlot = widgetSlot(),
                            overlay = {
                                val area by widgetArea.state.collectAsStateWithLifecycle()
                                WidgetMenu(area, widgetArea::onAction)
                            },
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
                                    onProfilePaused = { viewModel.onAction(HomeAction.Launch(it, null)) },
                                    onOpenSettings = { viewModel.onAction(HomeAction.OpenSettings) },
                                )
                            },
                        )
                        if (state.showOnboarding) {
                            val onboarding by onboardingViewModel.state.collectAsStateWithLifecycle()
                            // Done or "Not now" store `onboarding_done`; home's state then removes this layer.
                            OnboardingRoute(
                                state = onboarding,
                                effects = onboardingViewModel.effects,
                                onAction = onboardingViewModel::onAction,
                                onFinished = {},
                                onLeave = null,
                            )
                        } else {
                            val setDefault by setDefaultViewModel.state.collectAsStateWithLifecycle()
                            SetDefaultDialogs(setDefault, setDefaultViewModel.flow)
                        }
                    }
                }
            }
        } finally {
            AndroidTracer.end()
        }
    }

    /** The header widget (FR-WIDGET-01): null without a placement and in safe mode (the clock is shown). */
    @Composable
    private fun widgetSlot(): HomeWidgetSlot? {
        val area by widgetArea.state.collectAsStateWithLifecycle()
        if (!area.shown) return null
        val graph = appGraph
        return HomeWidgetSlot(replacesClock = area.replacesClock) { modifier ->
            WidgetArea(
                state = area,
                onAction = widgetArea::onAction,
                hostContent = {
                    id,
                    onLongPress,
                    m,
                    ->
                    HostedWidget(graph.widgetHost, id, onLongPress, m)
                },
                modifier = modifier,
            )
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

    /**
     * Keeps the IME hidden whenever no text field is open ([app.strata.launcher.feature.home.HomeUiState.imeAllowed]).
     * Closing search (Home press, Back, launch) ends the field's input session, which asks for a hide. On a loaded
     * device the IME can still be completing the show that opening search requested; that hide is then cancelled and
     * the late show leaves the keyboard up over home at rest (CI run 41820860155, API 34, T-HOME-09: hide
     * "onCancelled at PHASE_CLIENT_APPLY_ANIMATION", then `onShown`). Reacting to the IME actually being visible,
     * rather than issuing one hide at close time, makes the rest state hold whatever order the IME finishes in.
     */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun HideStrayIme(allowed: Boolean) {
        val visible = WindowInsets.isImeVisible
        LaunchedEffect(visible, allowed) {
            if (visible && !allowed) {
                searchCloseLog("IME visible without a text field -> hide")
                WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Drop half of the L1 icon cache when home goes to the background, all of it under real pressure.
        appGraph.iconProvider.trim(critical = level > ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
    }

    override fun onStart() {
        AndroidTracer.begin(TraceSections.HOME_START)
        try {
            super.onStart()
            appGraph.refreshThemeInputs()
            // Callbacks are missed while not registered: reconcile on every start (ANDROID_PLATFORM §3.1).
            appGraph.appCatalog.requestReconcile()
            appGraph.profiles.refresh()
            // ANDROID_PLATFORM §7: the host listens while home is started; widgets are re-created on startListening.
            appGraph.widgetHost.startListening()
            widgetArea.refresh()
        } finally {
            AndroidTracer.end()
        }
    }

    override fun onStop() {
        appGraph.widgetHost.stopListening()
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

                HomeEffect.OpenWidgetPicker -> {
                    startActivity(WidgetPickerActivity.intent(this))
                    return
                }

                HomeEffect.OpenThemes -> {
                    startActivity(Intent(this, ThemesActivity::class.java))
                    return
                }

                is HomeEffect.OpenIconChooser -> {
                    startActivity(IconChooserActivity.intent(this, effect.key))
                    return
                }

                else -> {
                    return
                }
            }
        startActivity(Intent(action).setClass(this, SettingsActivity::class.java))
    }

    override fun onResume() {
        AndroidTracer.begin(TraceSections.HOME_RESUME)
        try {
            super.onResume()
            viewModel.onAction(HomeAction.RefreshRole)
            onboardingViewModel.onAction(OnboardingAction.Resume)
            setDefaultViewModel.flow.refresh()
            // ARCHITECTURE §10 step 4: listener access (FR-NOTIF-01; revoked → badges vanish) and the contact permission.
            val graph = appGraph
            lifecycleScope.launch {
                try {
                    graph.badges.refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    graph.logger.w(TAG_BADGES, "listener access check failed", e)
                }
            }
            searchViewModel.onAction(SearchAction.Resume)
        } finally {
            AndroidTracer.end()
        }
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
}

private const val TAG_BADGES = "Badges"
