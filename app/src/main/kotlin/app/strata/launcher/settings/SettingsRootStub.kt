package app.strata.launcher.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.strata.launcher.R

object SettingsTestTags {
    const val ROOT = "settings_root"
}

/** Placeholder settings root; `:feature:settings` replaces it in M8. */
@Composable
fun SettingsRootStub(modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize().testTag(SettingsTestTags.ROOT)) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
            Text(text = stringResource(R.string.settings_placeholder_body), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
