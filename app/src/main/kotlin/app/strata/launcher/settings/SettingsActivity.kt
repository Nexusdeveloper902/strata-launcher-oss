package app.strata.launcher.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.strata.launcher.appGraph
import app.strata.launcher.core.platformapi.system.SystemIntent
import app.strata.launcher.feature.onboarding.OnboardingAction
import app.strata.launcher.feature.onboarding.OnboardingRoute
import app.strata.launcher.feature.onboarding.OnboardingViewModel
import app.strata.launcher.feature.onboarding.SetDefaultDialogs
import app.strata.launcher.feature.onboarding.SetDefaultViewModel
import app.strata.launcher.feature.settings.SettingsAction
import app.strata.launcher.feature.settings.SettingsEffect
import app.strata.launcher.feature.settings.SettingsPage
import app.strata.launcher.feature.settings.SettingsRoute
import app.strata.launcher.feature.settings.SettingsViewModel
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.onboarding.SystemScreenLauncher
import app.strata.launcher.theme.ThemeHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings, and the D-008 entry router for the app icon (`MAIN` + `LAUNCHER`):
 * onboarding when `onboarding_done` is false, otherwise an explicit intent to [HomeActivity] and
 * `finish()`. `APPLICATION_PREFERENCES` opens the settings root without routing; [ACTION_HIDDEN_APPS] opens the
 * hidden-apps page (FR-APPS-04, FR-LIST-06).
 */
class SettingsActivity : ComponentActivity() {
    private var screen by mutableStateOf<EntryRoute?>(null)
    private var page by mutableStateOf(SettingsPage.ROOT)
    private var startPage = SettingsPage.ROOT

    private val viewModel: SettingsViewModel by viewModels {
        viewModelFactory {
            initializer {
                val graph = appGraph
                SettingsViewModel(
                    settings = graph.settingsRepository,
                    launchLog = graph.launchLog,
                    appList = graph.appList,
                    overrides = graph.overrides,
                    lockService = graph.lockService,
                    systemIntents = graph.systemIntents,
                    logger = graph.logger,
                    roleController = graph.roleController,
                    ioDispatcher = graph.dispatchers.io,
                    privateSpaceSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM,
                    badges = graph.badges,
                    contacts = graph.contacts,
                    // J-09: Android 13+ blocks listener access for sideloaded apps until "Allow restricted settings".
                    restrictedSettingsHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                    handle = createSavedStateHandle(),
                )
            }
        }
    }

    /** First run from the launcher icon (D-008, FR-ONBOARD-01). */
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

    /** The "Default home" row's set-default flow (FR-SET-02). */
    private val setDefaultViewModel: SetDefaultViewModel by viewModels {
        viewModelFactory {
            initializer {
                val graph = appGraph
                SetDefaultViewModel(graph.roleController, graph.dispatchers.io, graph.logger)
            }
        }
    }

    private val systemScreens = SystemScreenLauncher(this) { appGraph.logger }

    /** FR-SEARCH-06: `READ_CONTACTS`, requested only after the contacts disclosure; the VM holds the request in flight. */
    private val contactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onAction(SettingsAction.ContactsPermissionResult(granted))
        }

    private val themeHost by lazy { ThemeHost(appGraph.themeRepository) }

    override fun onStop() {
        themeHost.onStop()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val kind = EntryRouter.classify(intent?.action, intent?.categories)
        if (intent?.action == ACTION_HIDDEN_APPS) startPage = SettingsPage.HIDDEN_APPS
        // Rotation or a locale change recreates the activity: keep the open sub-page.
        page =
            savedInstanceState
                ?.getString(STATE_PAGE)
                ?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
                ?: startPage
        appGraph.startTheme()
        systemScreens.bind(onboardingViewModel.setDefault)
        systemScreens.bind(setDefaultViewModel.flow)
        // The settings rows' home-settings screen (FR-SET-02); ignored unless the settings view model opened it.
        systemScreens.onResult { _, _ -> viewModel.onAction(SettingsAction.HomeSettingsResult) }
        // Same theme hooks as home: first-frame gate, first-frame and boot-success reports, LKG on onStop.
        themeHost.holdFirstFrame(window.decorView)
        setContent {
            themeHost.Content(window) {
                when (screen) {
                    EntryRoute.ONBOARDING -> {
                        val onboarding by onboardingViewModel.state.collectAsStateWithLifecycle()
                        OnboardingRoute(
                            state = onboarding,
                            effects = onboardingViewModel.effects,
                            onAction = onboardingViewModel::onAction,
                            onFinished = ::openHome,
                            onLeave = ::finish,
                        )
                    }

                    EntryRoute.SETTINGS_ROOT -> {
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        SettingsRoute(
                            state = state,
                            effects = viewModel.effects,
                            onAction = viewModel::onAction,
                            page = page,
                            onPage = { page = it },
                            startPage = startPage,
                            onBack = ::finish,
                            onNavigate = ::navigate,
                        )
                        val setDefault by setDefaultViewModel.state.collectAsStateWithLifecycle()
                        SetDefaultDialogs(setDefault, setDefaultViewModel.flow)
                    }

                    EntryRoute.HOME, null -> {
                        Unit
                    }
                }
            }
        }
        lifecycleScope.launch {
            val done =
                if (kind == EntryKind.LAUNCHER_ICON) {
                    appGraph.settingsRepository.settings
                        .first()
                        .onboardingDone
                } else {
                    true
                }
            show(EntryRouter.route(kind, done))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, page.name)
    }

    override fun onResume() {
        super.onResume()
        // The lock service may have been turned on or off in the system settings (FR-GEST-03).
        when (screen) {
            EntryRoute.SETTINGS_ROOT -> {
                viewModel.onAction(SettingsAction.Resume)
                setDefaultViewModel.flow.refresh()
            }

            EntryRoute.ONBOARDING -> {
                onboardingViewModel.onAction(OnboardingAction.Resume)
            }

            else -> {
                Unit
            }
        }
    }

    /** FR-SET-02: the set-default flow, or the system home settings (for a result, so the uninstall can follow). */
    private fun navigate(effect: SettingsEffect) {
        when (effect) {
            SettingsEffect.SetDefault -> {
                setDefaultViewModel.flow.setAsDefault()
            }

            is SettingsEffect.OpenHomeSettings -> {
                val chain = listOf(SystemIntent.HomeSettings) + appGraph.roleController.fallbackIntents()
                viewModel.onAction(SettingsAction.HomeSettingsLaunched(effect.forUninstall))
                if (!systemScreens.start(chain.distinct())) viewModel.onAction(SettingsAction.HomeSettingsResult)
            }

            SettingsEffect.RequestContacts -> {
                try {
                    contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                } catch (e: android.content.ActivityNotFoundException) {
                    viewModel.onAction(SettingsAction.ContactsPermissionResult(false))
                }
            }

            else -> {
                Unit
            }
        }
    }

    private fun show(route: EntryRoute) {
        appGraph.logger.d(TAG, "entry route $route")
        if (route == EntryRoute.HOME) openHome() else screen = route
    }

    private fun openHome() {
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        private const val TAG = "Entry"
        private const val STATE_PAGE = "settings_page"

        /** Explicit start from the list end (FR-LIST-06) and the empty-list action: the hidden-apps page. */
        const val ACTION_HIDDEN_APPS = "app.strata.launcher.action.HIDDEN_APPS"
    }
}
