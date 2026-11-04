package app.strata.launcher.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.strata.launcher.core.theme.store.ThemeRepository
import app.strata.launcher.core.theme.store.ThemeSummary

/**
 * Debug builds only (`src/debug`): the home menu's "Themes" opens this list to switch the active theme live
 * until the M5 theme library exists. Release and benchmark builds compile the no-op in `src/release`.
 */
object DebugThemeSwitch {
    const val AVAILABLE = true
    const val TAG_DIALOG = "debug_theme_switch"

    fun itemTag(id: String) = "debug_theme_$id"

    @Composable
    fun Dialog(
        repository: ThemeRepository,
        activeId: String,
        onDismiss: () -> Unit,
    ) {
        var themes by remember { mutableStateOf<List<ThemeSummary>>(emptyList()) }
        LaunchedEffect(repository) { themes = repository.library() }
        AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag(TAG_DIALOG),
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
            title = { Text("Theme (debug)") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    for (t in themes) {
                        TextButton(
                            onClick = {
                                repository.apply(t.id)
                                onDismiss()
                            },
                            enabled = t.applicable,
                            modifier = Modifier.testTag(itemTag(t.id)),
                        ) {
                            Text(if (t.id.equals(activeId, ignoreCase = true)) "• ${t.name}" else t.name)
                        }
                    }
                }
            },
        )
    }
}
