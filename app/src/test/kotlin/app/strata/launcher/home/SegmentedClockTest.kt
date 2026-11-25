package app.strata.launcher.home

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.strata.launcher.a11y.ContrastTest
import app.strata.launcher.core.theme.model.ClockStyle
import app.strata.launcher.core.theme.model.HourFormat
import app.strata.launcher.feature.home.HomeTestTags
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * T-THEME-59 (FR-THEME-37, VISUAL_SPEC §9.4): the `segmented` and `countdownBoard` clock styles on home, over a light
 * and a dark sample preset, in 12 h and 24 h, LTR and RTL. The clock speaks the fixed time (the "T−" label is not
 * spoken), its digits are drawn in `textPrimary`, only the board draws `accent` (its status lamp; the "T−" label is `textSecondary`), the board's date
 * is upper case, and with `clock.alignment: center` the clock and the date are centred within ±1 dp (as T-THEME-56).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SegmentedClockTest(
    private val preset: String,
    private val style: ClockStyle,
    private val h24: Boolean,
    private val rtl: Boolean,
) {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val screen = ClockScreen(compose)

    @Before
    fun fixTime() = screen.fixTime()

    @After
    fun restoreTime() = screen.restoreTime()

    @Test
    fun tTheme59_segmentedClock() {
        val activity =
            screen.start(preset, rtl) {
                it.copy(style = style, hourFormat = if (h24) HourFormat.H24 else HourFormat.H12, showSeconds = false)
            }
        val where = "$preset $style h24=$h24 rtl=$rtl"
        screen.assertFixedTime(activity, h24, seconds = false)
        screen.assertCentred(activity, where)

        val view = activity.window.decorView
        val clock = screen.nodes(view).first { it.config.getOrNull(SemanticsProperties.TestTag) == HomeTestTags.CLOCK }
        // Both sample presets fix their mode, so either variant of the pair is the one shown.
        val theme = ContrastTest.presetState(preset).themes.light
        val colors = theme.colors
        val textPx = screen.countColour(view, clock.boundsInRoot, colors.textPrimary)
        assertWithMessage("$where: digits in textPrimary").that(textPx).isGreaterThan(MIN_DIGIT_PIXELS)
        val date = screen.nodes(view).first { it.config.getOrNull(SemanticsProperties.TestTag) == HomeTestTags.DATE }
        val accentPx =
            screen.countColour(view, clock.boundsInRoot, colors.accent) +
                screen.countColour(view, date.boundsInRoot, colors.accent)
        if (style == ClockStyle.COUNTDOWN_BOARD) {
            assertWithMessage("$where: board lamp in accent").that(accentPx).isGreaterThan(MIN_ACCENT_PIXELS)
            val text =
                compose
                    .onNodeWithTag(HomeTestTags.DATE)
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(SemanticsProperties.Text)
                    ?.joinToString("") { it.text }
                    .orEmpty()
            assertWithMessage("$where: status line").that(text).isNotEmpty()
            assertWithMessage(
                "$where: status line is upper case",
            ).that(text).isEqualTo(text.uppercase(Locale.getDefault()))
        } else {
            assertWithMessage("$where: plain segmented clock draws no accent").that(accentPx).isEqualTo(0)
        }
    }

    companion object {
        private const val DARK = "graphite"
        private const val MIN_DIGIT_PIXELS = 500
        private const val MIN_ACCENT_PIXELS = 20

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-h24={2}-rtl={3}")
        fun parameters(): List<Array<Any>> {
            val flags = listOf(false, true)
            return listOf("paper", DARK).flatMap { p ->
                listOf(ClockStyle.SEGMENTED, ClockStyle.COUNTDOWN_BOARD).flatMap { s ->
                    flags.flatMap { h -> flags.map { r -> arrayOf<Any>(p, s, h, r) } }
                }
            }
        }
    }
}
