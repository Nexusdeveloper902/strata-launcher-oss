package app.strata.launcher.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.strata.launcher.appGraph
import app.strata.launcher.core.ui.StrataPlaceholderTheme
import app.strata.launcher.feature.onboarding.OnboardingStubScreen
import app.strata.launcher.home.HomeActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings, and the D-008 entry router for the app icon (`MAIN` + `LAUNCHER`):
 * onboarding when `onboarding_done` is false, otherwise an explicit intent to [HomeActivity] and
 * `finish()`. `APPLICATION_PREFERENCES` opens the settings root without routing.
 */
class SettingsActivity : ComponentActivity() {
    private var screen by mutableStateOf<EntryRoute?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val kind = EntryRouter.classify(intent?.action, intent?.categories)
        setContent {
            StrataPlaceholderTheme {
                when (screen) {
                    EntryRoute.ONBOARDING -> OnboardingStubScreen(onContinue = ::completeOnboarding)
                    EntryRoute.SETTINGS_ROOT -> SettingsRootStub()
                    EntryRoute.HOME, null -> Unit
                }
            }
        }
        lifecycleScope.launch {
            val done = if (kind == EntryKind.LAUNCHER_ICON) appGraph.onboardingStore.onboardingDone.first() else true
            show(EntryRouter.route(kind, done))
        }
    }

    private fun show(route: EntryRoute) {
        appGraph.logger.d(TAG, "entry route $route")
        if (route == EntryRoute.HOME) openHome() else screen = route
    }

    private fun completeOnboarding() {
        lifecycleScope.launch {
            appGraph.onboardingStore.setOnboardingDone(true)
            openHome()
        }
    }

    private fun openHome() {
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private companion object {
        const val TAG = "Entry"
    }
}
