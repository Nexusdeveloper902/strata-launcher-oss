package app.strata.launcher.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

object OnboardingTestTags {
    const val ROOT = "onboarding_stub"
    const val CONTINUE = "onboarding_continue"
}

/** M0 stand-in for the first-run flow (FR-ONBOARD-01, full flow in M6). "Continue" sets `onboarding_done`. */
@Composable
fun OnboardingStubScreen(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize().testTag(OnboardingTestTags.ROOT),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.onboarding_welcome_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(text = stringResource(R.string.onboarding_welcome_body), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onContinue, modifier = Modifier.testTag(OnboardingTestTags.CONTINUE)) {
                Text(text = stringResource(R.string.onboarding_continue))
            }
        }
    }
}
