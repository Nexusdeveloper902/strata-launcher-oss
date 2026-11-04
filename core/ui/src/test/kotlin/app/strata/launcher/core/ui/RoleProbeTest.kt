package app.strata.launcher.core.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.model.ColorRole
import app.strata.launcher.core.theme.parse.ThemeParser
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import app.strata.launcher.core.theme.resolve.ResolvedWallpaper
import app.strata.launcher.core.theme.resolve.ThemeGlobals
import app.strata.launcher.core.theme.resolve.ThemeResolver
import app.strata.launcher.core.theme.store.ThemePair
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The `:core:ui` role probe (TEST_PLAN T-THEME-02/04/06, screenshot halves; T-THEME-08 formula): every colour role
 * drawn by the element VISUAL_SPEC §6 assigns it, captured with Robolectric native graphics and sampled per region.
 * Roborazzi golden files are not set up, so "screenshot equal" compares two captures pixel by pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoleProbeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** One region per role: backdrop, sheet surface, label, secondary line, accent mark, text on accent, divider, pressed row, scrim. */
    @Composable
    private fun RoleProbe() {
        val c = Strata.colors
        Column(modifier = Modifier.size(PROBE_W.dp, PROBE_H.dp).strataBackdrop(Strata.theme, c).testTag(PROBE)) {
            Box(Modifier.size(PROBE_W.dp, CELL.dp)) // background (E(W) with show off = background)
            Box(Modifier.size(PROBE_W.dp, CELL.dp).background(c.surface))
            Box(Modifier.size(PROBE_W.dp, CELL.dp).background(c.textPrimary))
            Box(Modifier.size(PROBE_W.dp, CELL.dp).background(c.textSecondary))
            Box(Modifier.size(PROBE_W.dp, CELL.dp).background(c.accent))
            Box(Modifier.size(PROBE_W.dp, CELL.dp).background(c.onAccent))
            Box(Modifier.size(PROBE_W.dp, CELL.dp).background(c.divider))
            Box(Modifier.size(PROBE_W.dp, CELL.dp).background(c.highlight))
            Row(Modifier.fillMaxWidth().height(CELL.dp)) { Box(Modifier.size(PROBE_W.dp, CELL.dp).background(c.scrim)) }
        }
    }

    private val regions =
        listOf(
            ColorRole.BACKGROUND,
            ColorRole.SURFACE,
            ColorRole.TEXT_PRIMARY,
            ColorRole.TEXT_SECONDARY,
            ColorRole.ACCENT,
            ColorRole.ON_ACCENT,
            ColorRole.DIVIDER,
            ColorRole.HIGHLIGHT,
            ColorRole.SCRIM,
        )

    private var current by mutableStateOf(StrataDefault.resolved(systemDark = false))

    private fun capture(theme: ResolvedTheme): ImageBitmap {
        current = theme
        compose.waitForIdle()
        return snapshot()
    }

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                StrataTheme(current) { RoleProbe() }
            }
        }
    }

    /**
     * Draws the content view into a bitmap on Robolectric's native canvas. `captureToImage` waits for a frame
     * commit that the Robolectric window never reports, so the view is drawn directly instead.
     */
    private fun snapshot(): ImageBitmap {
        compose.waitForIdle()
        var bitmap: Bitmap? = null
        compose.runOnIdle {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            val b = Bitmap.createBitmap(PROBE_W, PROBE_H, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        return bitmap!!.asImageBitmap()
    }

    private fun ImageBitmap.at(role: ColorRole): Int {
        val row = regions.indexOf(role)
        return toPixelMap()[PROBE_W / 2, row * CELL + CELL / 2].toArgb()
    }

    private fun resolve(
        json: String,
        dark: Boolean = false,
        globals: ThemeGlobals = ThemeGlobals(),
    ): ResolvedTheme {
        val doc = (ThemeParser().parse(json.toByteArray()) as Outcome.Ok).value.document
        return ThemeResolver.resolve(doc, PlatformInputs(apiLevel = 26, systemDark = dark), globals).theme
    }

    private fun ImageBitmap.pixels(): IntArray {
        val map = toPixelMap()
        return IntArray(width * height) { map[it % width, it / width].toArgb() }
    }

    /** T-THEME-02 (S half): a minimal file renders identically to Strata Default. */
    @Test
    fun tTheme02_minimalFileRendersAsStrataDefault() {
        show()
        for (dark in listOf(false, true)) {
            val minimal =
                resolve("""{"schemaVersion":1,"id":"$ID","name":"Min"}""", dark)
            val a = capture(StrataDefault.resolved(systemDark = dark)).pixels()
            val b = capture(minimal).pixels()
            assertWithMessage("dark=$dark").that(b.contentEquals(a)).isTrue()
        }
    }

    /** T-THEME-04 (S half): each role set to magenta changes exactly its own element. */
    @Test
    fun tTheme04_eachRoleChangesItsElement() {
        show()
        val base = capture(StrataDefault.resolved(systemDark = false))
        for (role in regions) {
            val name = role.wire
            val t =
                resolve(
                    """{"schemaVersion":1,"id":"$ID","name":"M","colors":{"mode":"light","light":{"$name":"#FF00FF"}}}""",
                    globals = ThemeGlobals(enforceContrast = false),
                )
            val img = capture(t)
            for (other in regions) {
                if (other == role) {
                    assertWithMessage("$name region").that(img.at(other)).isEqualTo(MAGENTA)
                } else {
                    assertWithMessage("$name changed ${other.wire}").that(img.at(other)).isEqualTo(base.at(other))
                }
            }
        }
    }

    /** T-THEME-06 (S half): pure black turns background and scrim #000000 in dark; light is unaffected. */
    @Test
    fun tTheme06_pureBlack() {
        show()
        val json = """{"schemaVersion":1,"id":"$ID","name":"P","colors":{"pureBlack":true}}"""
        val dark = capture(resolve(json, dark = true))
        assertThat(dark.at(ColorRole.BACKGROUND)).isEqualTo(BLACK)
        assertThat(dark.at(ColorRole.SCRIM)).isEqualTo(BLACK)
        assertThat(
            dark.at(ColorRole.SURFACE),
        ).isEqualTo(capture(StrataDefault.resolved(systemDark = true)).at(ColorRole.SURFACE))
        val light = capture(resolve(json, dark = false)).pixels()
        assertThat(light.contentEquals(capture(StrataDefault.resolved(systemDark = false)).pixels())).isTrue()
    }

    /**
     * T-THEME-08 (S half on the JVM): over a solid `#3366CC` "wallpaper" with dim 0.4 and opacity 0.2, the backdrop
     * pixel equals the §7.2 formula ±1/255 per channel. The device half (real wallpaper) is instrumented.
     */
    @Test
    fun tTheme08_effectiveBackgroundFormula() {
        val base = StrataDefault.resolved(systemDark = true)
        val theme =
            base.copy(
                wallpaper = ResolvedWallpaper(show = true, dim = 0.4, backgroundOpacity = 0.2, imageSha256 = null),
            )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                StrataTheme(theme) {
                    Box(Modifier.size(40.dp).background(Color(WALLPAPER)).testTag(PROBE)) {
                        Box(Modifier.size(40.dp).strataBackdrop(Strata.theme, Strata.colors))
                    }
                }
            }
        }
        val px = snapshot().toPixelMap()[20, 20].toArgb()

        fun over(
            fg: Int,
            a: Double,
            bg: Int,
        ): Int {
            fun ch(
                c: Int,
                s: Int,
            ) = (c shr s) and 0xFF
            val r = (ch(fg, 16) * a + ch(bg, 16) * (1 - a)).roundToInt()
            val g = (ch(fg, 8) * a + ch(bg, 8) * (1 - a)).roundToInt()
            val b = (ch(fg, 0) * a + ch(bg, 0) * (1 - a)).roundToInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val expected = over(theme.colors.background, 0.2, over(theme.colors.scrim, 0.4, WALLPAPER))
        for (s in listOf(16, 8, 0)) {
            assertWithMessage("channel $s").that(abs(((px shr s) and 0xFF) - ((expected shr s) and 0xFF))).isAtMost(1)
        }
    }

    /**
     * Cold start (review item 1): before a loaded theme was shown the switch snaps, so the first frame with the
     * theme already draws its backdrop; afterwards a switch cross-fades the backdrop only (THEME_SYSTEM §12).
     */
    @Test
    fun themeSwitch_snapsUntilLoaded_thenCrossFadesTheBackdrop() {
        var pair by mutableStateOf(ThemePair.DEFAULT)
        var crossFade by mutableStateOf(false)
        var composedBackground = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                StrataTheme(pair, crossFade = crossFade) {
                    val bg = Strata.colors.background.toArgb()
                    SideEffect { composedBackground = bg }
                    Box(
                        Modifier
                            .size(
                                PROBE_W.dp,
                                PROBE_H.dp,
                            ).strataBackdrop(Strata.theme, Strata.colors, Strata.backdropFade),
                    )
                }
            }
        }
        compose.waitForIdle()
        val light = StrataDefault.resolved(systemDark = false).colors.background
        val dark = StrataDefault.resolved(systemDark = true)
        compose.mainClock.autoAdvance = false

        fun pixel() = snapshot().toPixelMap()[PROBE_W / 2, PROBE_H / 2].toArgb()
        compose.runOnUiThread { pair = ThemePair(dark, dark) }
        // Every frame draws the backdrop of the tokens composed for it: never Default under the new theme.
        repeat(3) {
            compose.mainClock.advanceTimeByFrame()
            assertThat(pixel()).isEqualTo(composedBackground)
        }
        assertThat(pixel()).isEqualTo(dark.colors.background)

        compose.runOnUiThread {
            crossFade = true
            pair = ThemePair.DEFAULT
        }
        // Content tokens switch at once; the backdrop passes through intermediate colours.
        repeat(MID_FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertThat(composedBackground).isEqualTo(light)
        val mid = pixel()
        assertThat(mid).isNotEqualTo(light)
        assertThat(mid).isNotEqualTo(dark.colors.background)
        repeat(END_FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertThat(pixel()).isEqualTo(light)
    }

    private companion object {
        const val ID = "11111111-2222-4333-8444-555555555555"
        const val PROBE = "role_probe"
        const val MID_FRAMES = 10
        const val END_FRAMES = 20
        const val PROBE_W = 40
        const val CELL = 10
        const val PROBE_H = 90
        val MAGENTA = 0xFFFF00FF.toInt()
        val BLACK = 0xFF000000.toInt()
        val WALLPAPER = 0xFF3366CC.toInt()
    }
}
