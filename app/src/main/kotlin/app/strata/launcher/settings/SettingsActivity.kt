package app.strata.launcher.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.strata.launcher.appGraph
import app.strata.launcher.core.ui.StrataDefaultTheme
import app.strata.launcher.feature.onboarding.OnboardingStubScreen
import app.strata.launcher.feature.settings.SettingsAction
import app.strata.launcher.feature.settings.SettingsPage
import app.strata.launcher.feature.settings.SettingsRoute
import app.strata.launcher.feature.settings.SettingsViewModel
import app.strata.launcher.home.HomeActivity
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
                )
            }
        }
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
        setContent {
            StrataDefaultTheme {
                when (screen) {
                    EntryRoute.ONBOARDING -> {
                        OnboardingStubScreen(onContinue = ::completeOnboarding)
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
                        )
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
        if (screen == EntryRoute.SETTINGS_ROOT) viewModel.onAction(SettingsAction.Resume)
    }

    private fun show(route: EntryRoute) {
        appGraph.logger.d(TAG, "entry route $route")
        if (route == EntryRoute.HOME) openHome() else screen = route
    }

    private fun completeOnboarding() {
        lifecycleScope.launch {
            appGraph.settingsRepository.update { it.copy(onboardingDone = true) }
            openHome()
        }
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
