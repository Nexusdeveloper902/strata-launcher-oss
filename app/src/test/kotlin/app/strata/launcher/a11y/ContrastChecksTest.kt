package app.strata.launcher.a11y

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.ui.Strata
import app.strata.launcher.core.ui.StrataTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pixel check itself (review of PR #32): text drawn in the background colour fails at 1:1 instead of being skipped
 * as "not drawn", and a decorative edge in the line box cannot stand in for low-contrast text.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContrastChecksTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The original bug on Midnight OLED: a `Text` with no colour on #000000 drew Compose's default black. */
    @Test
    fun noColourTextOnMidnightOled_fails() {
        val theme = ContrastTest.presetState("midnight-oled").themes.dark
        compose.setContent {
            StrataTheme(theme) {
                Box(Modifier.fillMaxSize().background(Strata.colors.background)) {
                    // As on main before the fix: no content colour from the theme, so Compose's default black.
                    CompositionLocalProvider(LocalContentColor provides Color.Black) { Text("Vibration feedback") }
                }
            }
        }
        val failures = compose.contrastFailures()
        assertThat(failures.map { it.text }).containsExactly("Vibration feedback")
        assertThat(failures.single().ratio).isLessThan(1.05)
    }

    /** The same text with the theme's content colour passes. */
    @Test
    fun themedTextOnMidnightOled_passes() {
        val theme = ContrastTest.presetState("midnight-oled").themes.dark
        compose.setContent {
            StrataTheme(theme) {
                Box(Modifier.fillMaxSize().background(Strata.colors.background)) { Text("Vibration feedback") }
            }
        }
        assertThat(compose.contrastFailures()).isEmpty()
    }

    /** Low-contrast text next to a high-contrast mark in the same line box still fails. */
    @Test
    fun strongEdgeDoesNotHideWeakText() {
        compose.setContent {
            Column(Modifier.fillMaxSize().background(Color(0xFF101010))) {
                Text("Dim text with a bright dot •", color = Color(0xFF2A2A2A), fontSize = 16.sp)
            }
        }
        // Simulated sample: a large dim-text cluster and a few bright pixels.
        val region = List(400) { 0xFF101010.toInt() } + List(80) { 0xFF2A2A2A.toInt() } + List(3) { -1 }
        assertThat(contrastOf(region).ratio).isLessThan(2.0)
        assertThat(compose.contrastFailures()).isNotEmpty()
    }
}
