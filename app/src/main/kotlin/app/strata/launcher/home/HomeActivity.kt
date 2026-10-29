package app.strata.launcher.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.strata.launcher.BuildConfig
import app.strata.launcher.appGraph
import app.strata.launcher.core.platformapi.system.SystemIntent
import app.strata.launcher.core.ui.StrataPlaceholderTheme
import app.strata.launcher.feature.home.HomeAction
import app.strata.launcher.feature.home.HomeScreen
import app.strata.launcher.feature.home.HomeViewModel
import app.strata.launcher.feature.onboarding.OnboardingStubScreen
import app.strata.launcher.platform.system.SystemIntentFactory

/**
 * The HOME activity (ANDROID_PLATFORM §1, §22). Edge-to-edge over the wallpaper. Reads only
 * action and category of incoming intents; extras are ignored (ARCHITECTURE §13, T-SEC-03).
 */
class HomeActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels {
        viewModelFactory {
            initializer {
                val graph = appGraph
                HomeViewModel(graph.roleController, graph.onboardingStore, graph.dispatchers.io)
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
        setContent {
            StrataPlaceholderTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) {
                    viewModel.effects.collect { effect -> appGraph.logger.d(TAG, "effect $effect") }
                }
                HomeScreen(
                    state = state,
                    showSetDefaultButton = BuildConfig.DEBUG,
                    onSetDefault = ::requestDefaultHome,
                )
                if (state.showOnboarding) {
                    OnboardingStubScreen(onContinue = { viewModel.onAction(HomeAction.CompleteOnboarding) })
                }
            }
        }
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
