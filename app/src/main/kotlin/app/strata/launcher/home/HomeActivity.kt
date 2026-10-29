package app.strata.launcher.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.strata.launcher.BuildConfig
import app.strata.launcher.appGraph
import app.strata.launcher.core.platformapi.system.SystemIntent
import app.strata.launcher.core.ui.StrataDefaultTheme
import app.strata.launcher.di.localeTags
import app.strata.launcher.feature.home.HomeAction
import app.strata.launcher.feature.home.HomeEffect
import app.strata.launcher.feature.home.HomeRoute
import app.strata.launcher.feature.home.HomeViewModel
import app.strata.launcher.feature.onboarding.OnboardingStubScreen
import app.strata.launcher.platform.system.SystemIntentFactory
import app.strata.launcher.settings.SettingsActivity

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
                )
            }
        }
    }

    private var roleRequestStartedAt = 0L

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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        appGraph.startCatalog()
        appGraph.attachWindow(window.decorView)
        setContent {
            StrataDefaultTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                // FR-LIFE-01 / T-PERF-01: fully drawn once the list (snapshot) is available.
                ReportDrawnWhen { state.listReady }
                HomeRoute(
                    state = state,
                    effects = viewModel.effects,
                    onAction = viewModel::onAction,
                    onNavigate = ::navigate,
                    showSetDefaultButton = BuildConfig.DEBUG,
                    onSetDefault = ::requestDefaultHome,
                )
                if (state.showOnboarding) {
                    OnboardingStubScreen(onContinue = { viewModel.onAction(HomeAction.CompleteOnboarding) })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Callbacks are missed while not registered: reconcile on every start (ANDROID_PLATFORM §3.1).
        appGraph.appCatalog.requestReconcile()
    }

    override fun onDestroy() {
        appGraph.attachWindow(null)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        appGraph.locales.value = localeTags(this)
    }

    private fun navigate(effect: HomeEffect) {
        val action =
            when (effect) {
                HomeEffect.OpenSettings -> Intent.ACTION_APPLICATION_PREFERENCES
                HomeEffect.OpenHiddenApps -> SettingsActivity.ACTION_HIDDEN_APPS
                else -> return
            }
        startActivity(Intent(action).setClass(this, SettingsActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAction(HomeAction.RefreshRole)
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
