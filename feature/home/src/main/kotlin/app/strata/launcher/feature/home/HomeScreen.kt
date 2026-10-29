package app.strata.launcher.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

object HomeTestTags {
    const val PLACEHOLDER = "home_placeholder"
    const val APP_MODE_BANNER = "home_app_mode_banner"
    const val SET_DEFAULT_BUTTON = "home_set_default_button"
}

/**
 * M0 placeholder home (T-HOME-01) drawn over the wallpaper, with the APP_MODE banner stub
 * (T-HOME-02). [showSetDefaultButton] is true only in debug builds (IMPLEMENTATION_ROADMAP M0).
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    showSetDefaultButton: Boolean,
    onSetDefault: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.showAppModeBanner) {
                AppModeBanner(showSetDefaultButton = showSetDefaultButton, onSetDefault = onSetDefault)
            }
        }
        Text(
            text = stringResource(R.string.home_placeholder),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.Center).testTag(HomeTestTags.PLACEHOLDER),
        )
    }
}

@Composable
private fun AppModeBanner(showSetDefaultButton: Boolean, onSetDefault: () -> Unit) {
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
