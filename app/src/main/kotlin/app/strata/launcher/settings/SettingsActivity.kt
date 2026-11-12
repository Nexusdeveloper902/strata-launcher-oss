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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.strata.launcher.BuildConfig
import app.strata.launcher.appGraph
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.platformapi.system.SystemIntent
import app.strata.launcher.feature.onboarding.OnboardingAction
import app.strata.launcher.feature.onboarding.OnboardingRoute
import app.strata.launcher.feature.onboarding.OnboardingViewModel
import app.strata.launcher.feature.onboarding.SetDefaultDialogs
import app.strata.launcher.feature.onboarding.SetDefaultViewModel
import app.strata.launcher.feature.settings.BackupAction
import app.strata.launcher.feature.settings.BackupEffect
import app.strata.launcher.feature.settings.BackupViewModel
import app.strata.launcher.feature.settings.IconCoverageViewModel
import app.strata.launcher.feature.settings.SettingsAction
import app.strata.launcher.feature.settings.SettingsEffect
import app.strata.launcher.feature.settings.SettingsPage
import app.strata.launcher.feature.settings.SettingsRoute
import app.strata.launcher.feature.settings.SettingsViewModel
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.onboarding.SystemScreenLauncher
import app.strata.launcher.theme.ThemeHost
import app.strata.launcher.theme.ThemesActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
                    versionName = BuildConfig.VERSION_NAME,
                    readAsset = { path -> assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) } },
                )
            }
        }
    }

    /** Settings -> Icons (FR-SET-07); computes nothing until the page is shown. */
    private val iconCoverage: IconCoverageViewModel by viewModels {
        viewModelFactory {
            initializer {
                val graph = appGraph
                IconCoverageViewModel(graph.appList, graph.iconProvider, graph.systemIntents, graph.logger)
            }
        }
    }

    /** Settings → Backup (FR-BACKUP-01…03); the picker in flight lives in its saved state. */
    private val backupViewModel: BackupViewModel by viewModels {
        viewModelFactory {
            initializer {
                val graph = appGraph
                BackupViewModel(
                    service = graph.backup,
                    logger = graph.logger,
                    handle = createSavedStateHandle(),
                    hasWorkProfile = graph.profiles.profiles.map { list -> list.any { it.kind == ProfileKind.WORK } },
                )
            }
        }
    }

    /** ARCHITECTURE §12.2: `ACTION_CREATE_DOCUMENT`, `application/json`. */
    private val createBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri ->
            backupViewModel.onAction(BackupAction.ExportTo(uri?.toString()))
        }

    /** THEME_SYSTEM §3.1 "Backup restore" types. */
    private val openBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            backupViewModel.onAction(BackupAction.RestoreFrom(uri?.toString()))
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

    /** One stream for both view models' messages (toasts) and navigation effects. */
    private val settingsEffects by lazy { merge(viewModel.effects, iconCoverage.effects) }

    override fun onStop() {
        themeHost.onStop()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val kind = EntryRouter.classify(intent?.action, intent?.categories)
        startPage =
            when (intent?.action) {
                ACTION_HIDDEN_APPS -> SettingsPage.HIDDEN_APPS
                ACTION_PRIVACY -> SettingsPage.PRIVACY
                else -> SettingsPage.ROOT
            }
        // Rotation or a locale change recreates the activity: keep the open sub-page.
        page =
            savedInstanceState
                ?.getString(STATE_PAGE)
                ?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
                // The licence text lives only in the view model: after process death show the list, not a blank page.
                ?.let {
                    if (it == SettingsPage.LICENCE_TEXT &&
                        viewModel.state.value.licenceText == null
                    ) {
                        SettingsPage.LICENCES
                    } else {
                        it
                    }
                }
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
                        val icons by iconCoverage.state.collectAsStateWithLifecycle()
                        val backup by backupViewModel.state.collectAsStateWithLifecycle()
                        SettingsRoute(
                            state = state,
                            effects = settingsEffects,
                            onAction = viewModel::onAction,
                            page = page,
                            onPage = { page = it },
                            startPage = startPage,
                            onBack = ::finish,
                            onNavigate = ::navigate,
                            icons = icons,
                            onIconsAction = iconCoverage::onAction,
                            backup = backup,
                            onBackupAction = backupViewModel::onAction,
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
            repeatOnLifecycle(Lifecycle.State.STARTED) { backupViewModel.effects.collect(::onBackupEffect) }
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

            SettingsEffect.OpenThemes -> {
                startActivity(Intent(this, ThemesActivity::class.java))
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

    private fun onBackupEffect(effect: BackupEffect) {
        val started =
            try {
                when (effect) {
                    is BackupEffect.CreateDocument -> {
                        createBackup.launch(effect.suggestedName)
                    }

                    BackupEffect.OpenDocument -> {
                        openBackup.launch(BACKUP_OPEN_TYPES)
                    }

                    is BackupEffect.ShareDocument -> {
                        val send =
                            Intent(Intent.ACTION_SEND)
                                .setType(BACKUP_MIME)
                                .putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(effect.uri))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(Intent.createChooser(send, null))
                    }
                }
                true
            } catch (e: android.content.ActivityNotFoundException) {
                appGraph.logger.w(TAG, "no handler for a backup document", e)
                false
            }
        if (!started) backupViewModel.onAction(BackupAction.PickerUnavailable)
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
        private const val BACKUP_MIME = "application/json"

        /** THEME_SYSTEM §3.1: backup restore accepts these types (not offered to other apps). */
        private val BACKUP_OPEN_TYPES = arrayOf("application/json", "application/octet-stream", "text/plain")

        /** Explicit start from the list end (FR-LIST-06) and the empty-list action: the hidden-apps page. */
        const val ACTION_HIDDEN_APPS = "app.strata.launcher.action.HIDDEN_APPS"

        /** Explicit start from [PermissionUsageActivity] (FR-SET-03): the Privacy & permissions page. */
        const val ACTION_PRIVACY = "app.strata.launcher.action.PRIVACY"
    }
}
