package app.strata.launcher.theme

import androidx.compose.runtime.Composable
import app.strata.launcher.core.theme.store.ThemeRepository

/** Release and benchmark builds: no theme switch until the M5 theme library (see `src/debug`). */
object DebugThemeSwitch {
    const val AVAILABLE = false

    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun Dialog(
        repository: ThemeRepository,
        activeId: String,
        onDismiss: () -> Unit,
    ) = Unit
}
