package app.strata.launcher.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * M0 stand-in for the theme pipeline. M1 replaces it with `ResolvedTheme` → CompositionLocals
 * (ARCHITECTURE §2 `:core:ui`).
 */
@Composable
fun StrataPlaceholderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
