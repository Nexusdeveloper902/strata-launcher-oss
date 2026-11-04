package app.strata.launcher.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.theme.parse.ThemeParser
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import app.strata.launcher.core.theme.resolve.ThemeResolver
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Typography (T-THEME-09 U/C, T-THEME-10), animation scaling (T-THEME-17) and row growth (T-THEME-50, C proxy). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real font metrics for the width and height measurements
class ThemeRenderingTest {
    @get:Rule
    val compose = createComposeRule()

    private fun resolve(
        groups: String,
        platform: PlatformInputs = PlatformInputs(apiLevel = 34),
    ): ResolvedTheme {
        val extra = if (groups.isEmpty()) "" else ",$groups"
        val json = """{"schemaVersion":1,"id":"11111111-2222-4333-8444-555555555555","name":"T"$extra}"""
        val doc = (ThemeParser().parse(json.toByteArray()) as Outcome.Ok).value.document
        return ThemeResolver.resolve(doc, platform).theme
    }

    // ---- T-THEME-09 / T-THEME-10 --------------------------------------------------------------------

    @Test
    fun tTheme09_typographyFieldsReachTheStyles() {
        val t =
            typographyOf(
                resolve(""""typography":{"scale":1.5,"labelWeight":700,"headingWeight":300,"letterSpacingEm":0.1}"""),
            )
        assertThat(t.label.fontSize.value).isEqualTo(24f) // 16 sp × 1.5; the system font scale multiplies in sp
        assertThat(t.label.fontWeight?.weight).isEqualTo(700)
        assertThat(t.title.fontWeight?.weight).isEqualTo(300)
        assertThat(t.label.letterSpacing).isEqualTo(0.1f.em)
        assertThat(
            typographyOf(resolve(""""typography":{"scale":0.8}""")).label.fontSize.value,
        ).isWithin(0.01f).of(12.8f)
    }

    /** A wider scale and more letter spacing measure wider; system 2.0 × theme 1.5 still fits the label's height. */
    @Test
    fun tTheme09_measuredWidthFollowsScaleAndSpacing() {
        var theme by mutableStateOf(resolve(""""typography":{"scale":0.8}"""))
        var fontScale by mutableStateOf(1f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                StrataTheme(
                    theme,
                ) { Text("Calendar", style = Strata.typography.label, modifier = Modifier.testTag(LABEL)) }
            }
        }

        fun width() = compose.onNodeWithTag(LABEL).getBoundsInRoot().let { it.right.value - it.left.value }

        fun height() = compose.onNodeWithTag(LABEL).getBoundsInRoot().let { it.bottom.value - it.top.value }
        val small = width()
        theme = resolve(""""typography":{"scale":1.5}""")
        compose.waitForIdle()
        val large = width()
        theme = resolve(""""typography":{"scale":1.5,"letterSpacingEm":0.2}""")
        compose.waitForIdle()
        val spaced = width()
        assertThat(large).isGreaterThan(small)
        assertThat(spaced).isGreaterThan(large)
        fontScale = 2f
        compose.waitForIdle()
        // 24 sp of text at font scale 2.0 (Android's non-linear scaling): the box holds the whole line.
        val lineHeight = Density(1f, 2f).run { (LABEL_SP * 1.5f * LINE).sp.toPx() }
        assertThat(height()).isAtLeast(lineHeight - 1f)
    }

    /** T-THEME-10: with the option on, text below 14 sp uses the system font without theme letter spacing. */
    @Test
    fun tTheme10_smallTextUsesSystemFont() {
        val on = typographyOf(resolve(""""typography":{"smallTextUsesSystemFont":true,"letterSpacingEm":0.1}"""))
        val off = typographyOf(resolve(""""typography":{"letterSpacingEm":0.1}"""))
        for (style in listOf(on.section, on.caption, on.index)) {
            assertThat(style.fontSize.value).isLessThan(14f)
            assertThat(style.fontFamily).isEqualTo(FontFamily.Default)
        }
        assertThat(on.caption.letterSpacing).isEqualTo(0f.em)
        assertThat(off.caption.letterSpacing).isEqualTo(0.1f.em)
        assertThat(on.label.letterSpacing).isEqualTo(0.1f.em) // 16 sp label keeps the theme font settings
    }

    // ---- T-THEME-17 -------------------------------------------------------------------------------

    /** Time until the sheet's content stops moving, on the Compose test clock. */
    private fun sheetSettleMs(theme: ResolvedTheme): Long {
        var current by mutableStateOf(theme)
        var shown by mutableStateOf(false)
        compose.setContent {
            StrataTheme(current) {
                if (shown) StrataSheet(onDismiss = {}) { Box(Modifier.size(20.dp).testTag(SHEET_CONTENT)) }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { shown = true }
        // Frame by frame: when the content first appears, then when it stops moving.
        val tops = ArrayList<Float?>()
        repeat(STEPS) {
            compose.mainClock.advanceTimeBy(STEP_MS)
            val nodes = compose.onAllNodesWithTag(SHEET_CONTENT, useUnmergedTree = true).fetchSemanticsNodes()
            tops += nodes.singleOrNull()?.boundsInRoot?.top
        }
        val appeared = tops.indexOfFirst { it != null }
        val final = tops.last()
        return (tops.indexOfFirst { it == final } - appeared) * STEP_MS
    }

    @Test
    fun tTheme17_half() {
        val ms = sheetSettleMs(resolve(""""animation":{"durationScale":0.5}"""))
        assertWithMessage("0.5 × $MEDIUM_MS").that(ms).isIn(MEDIUM_MS / 2L - 2 * STEP_MS..MEDIUM_MS / 2L + 2 * STEP_MS)
    }

    @Test
    fun tTheme17_double() {
        val ms = sheetSettleMs(resolve(""""animation":{"durationScale":2.0}"""))
        assertWithMessage("2.0 × $MEDIUM_MS").that(ms).isIn(MEDIUM_MS * 2L - 2 * STEP_MS..MEDIUM_MS * 2L + 2 * STEP_MS)
    }

    @Test
    fun tTheme17_systemZero_noAnimationForAnyThemeScale() {
        val off =
            resolve(""""animation":{"durationScale":2.0}""", PlatformInputs(apiLevel = 34, animatorDurationScale = 0.0))
        assertThat(off.animation.durationMs(MEDIUM_MS)).isEqualTo(0)
        assertThat(sheetSettleMs(off)).isEqualTo(0L)
    }

    /** Effective = base × theme × system; the system factor is applied by Compose (`MotionDurationScale`). */
    @Test
    fun tTheme17_effectiveScale() {
        val t =
            resolve(""""animation":{"durationScale":0.5}""", PlatformInputs(apiLevel = 34, animatorDurationScale = 2.0))
        assertThat(t.animation.durationScale).isEqualTo(1.0)
        assertThat(t.animation.durationMs(MEDIUM_MS)).isEqualTo(MEDIUM_MS / 2)
        val removed = resolve("", PlatformInputs(apiLevel = 34, animatorsEnabled = false))
        assertThat(removed.animation.enabled).isFalse()
    }

    // ---- T-THEME-50 (C proxy; the screenshot matrix needs Roborazzi) -----------------------------------

    /** At the smallest row height, theme scale 1.5 and font 2.0, a row grows instead of clipping its label. */
    @Test
    fun tTheme50_rowGrowsWithText() {
        val theme = resolve(""""list":{"rowHeightDp":48},"typography":{"scale":1.5}""")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                StrataTheme(theme) {
                    Box(Modifier.width(320.dp)) {
                        AppRow(
                            label = "Calendar",
                            onClick = {},
                            profileKind = ProfileKind.MAIN,
                            modifier = Modifier.testTag(LABEL),
                        )
                    }
                }
            }
        }
        val row = compose.onNodeWithTag(LABEL).getBoundsInRoot()
        val lineHeight = Density(1f, 2f).run { (LABEL_SP * 1.5f * LINE).sp.toPx() }
        assertThat(row.bottom.value - row.top.value).isAtLeast(lineHeight)
        assertThat(row.bottom.value - row.top.value).isAtLeast(48f) // the touch-target minimum stays
    }

    private companion object {
        const val LABEL = "label"
        const val LABEL_SP = 16f
        const val LINE = 1.3f
        const val SHEET_CONTENT = "sheet_content"
        const val STEP_MS = 16L
        const val STEPS = 60
    }
}
