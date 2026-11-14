package app.strata.launcher.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.strata.launcher.testing.fixtures.perf.PerfBudgets
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * Theme macrobenchmarks on RD-1; the budgets are checked on a physical device only ([BudgetGate]). Run with `./gradlew :benchmark:connectedBenchmarkAndroidTest`
 * against the `benchmark` build type (`app.strata.launcher.benchmark`). Never run in this repo's container.
 *
 * - T-THEME-48 (NFR-THEME-08): editor open; toggle discrete controls (preview light/dark), then drag the accent hue
 *   slider for 5 s. FrameTimingMetric: discrete changes show in the next frame; the drag holds ≥ 55 fps.
 * - T-THEME-53 (NFR-PERF-06): apply a theme in the library; `strata.themeApply` spans the tap to the first frame drawn
 *   with the new theme (async trace section, API 29+). Budget ≤ 300 ms.
 */
@RunWith(AndroidJUnit4::class)
class ThemeBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @get:Rule
    val test = TestName()

    private fun MacrobenchmarkScope.openLibrary() {
        homeReady()
        device.openThemeLibraryFromHome()
    }

    private fun MacrobenchmarkScope.openEditor() {
        openLibrary()
        checkNotNull(device.findObject(By.res(NEW_THEME))) { "New theme not found" }.click()
        device.wait(Until.hasObject(By.res(EDITOR_PREVIEW)), TIMEOUT_MS)
    }

    private fun MacrobenchmarkScope.find(
        res: String,
        scroll: UiObject2?,
    ): UiObject2? {
        device.findObject(By.res(res))?.let { return it }
        return scroll?.scrollUntil(Direction.DOWN, Until.findObject(By.res(res)))
    }

    /** T-THEME-48, discrete half: preview light/dark toggles (one frame each). */
    @Test
    fun tTheme48_discreteToggles() =
        rule.measureRepeated(
            packageName = TARGET,
            metrics = listOf(FrameTimingMetric()),
            iterations = 5,
            setupBlock = { openEditor() },
        ) {
            val toggle = checkNotNull(device.findObject(By.res(PREVIEW_DARK))) { "preview dark toggle not found" }
            repeat(10) {
                toggle.click()
                device.waitForIdle()
            }
        }

    /** T-THEME-48, drag half: the accent hue slider dragged back and forth for about 5 s (≥ 55 fps). */
    @Test
    fun tTheme48_hueDrag() =
        rule.measureRepeated(
            packageName = TARGET,
            metrics = listOf(FrameTimingMetric()),
            iterations = 5,
            setupBlock = {
                openEditor()
                val scroll = device.findObject(By.res(EDITOR_SCROLL))
                checkNotNull(find(ACCENT_SWATCH, scroll)) { "accent swatch not found" }.click()
                checkNotNull(find(ACCENT_HUE, scroll)) { "accent hue slider not found" }
            },
        ) {
            val slider = checkNotNull(device.findObject(By.res(ACCENT_HUE))) { "accent hue slider not found" }
            val b = slider.visibleBounds
            val y = b.centerY()
            val left = b.left + b.width() / 10
            val right = b.right - b.width() / 10
            // UiDevice.swipe moves one step every ~5 ms: 2 × 500 steps ≈ 5 s of continuous dragging.
            device.swipe(left, y, right, y, DRAG_STEPS)
            device.swipe(right, y, left, y, DRAG_STEPS)
        }

    /** T-THEME-53: tap → first frame with the applied theme (≤ 300 ms), alternating two built-ins. */
    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun tTheme53_applyLatency() {
        var flip = false
        rule.measureRepeated(
            packageName = TARGET,
            metrics =
                listOf(
                    TraceSectionMetric(APPLY_SECTION, TraceSectionMetric.Mode.First),
                    FrameTimingMetric(),
                ),
            iterations = 10,
            setupBlock = { openLibrary() },
        ) {
            flip = !flip
            val id = if (flip) PAPER else STRATA_DEFAULT
            val row = checkNotNull(device.findObject(By.res("theme_row_$id"))) { "theme row $id not found" }
            row.click()
            device.waitForIdle()
        }
        BudgetGate.check(
            javaClass,
            test.methodName,
            BudgetGate.maxAtMost("${APPLY_SECTION}FirstMs", PerfBudgets.THEME_APPLY_MS, "T-THEME-53"),
        )
    }

    /**
     * T-THEME-44 on the device (NFR-THEME-02, M9 "theme parse ≤ 5 ms typical"): with a built-in file theme (Paper,
     * < 8 KiB) active, cold starts parse it at boot; every `strata.themeParse` slice is reported and the median must
     * stay ≤ 5 ms. The 256 KiB (≤ 30 ms) case has no device fixture yet; core:theme PerformanceTest covers it on the JVM.
     * `strata.themeBoot` and `strata.themeResolve` are reported for the "≤ 150 ms added to cold start" check.
     */
    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun tTheme44_parseOnColdStart() {
        try {
            parseOnColdStart()
        } finally {
            // Leave the default theme for the other benchmarks (TEST_PLAN §0 "default theme").
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).applyTheme(STRATA_DEFAULT)
        }
        BudgetGate.check(
            javaClass,
            test.methodName,
            BudgetGate.medianAtMost(
                SliceDurationsMetric.name(PARSE_SECTION),
                PerfBudgets.THEME_PARSE_TYPICAL_MS,
                "T-THEME-44",
            ),
        )
    }

    @OptIn(ExperimentalMetricApi::class)
    private fun parseOnColdStart() =
        rule.measureRepeated(
            packageName = TARGET,
            metrics =
                listOf(
                    SliceDurationsMetric(PARSE_SECTION),
                    TraceSectionMetric("strata.themeBoot", TraceSectionMetric.Mode.First),
                    TraceSectionMetric("strata.themeResolve", TraceSectionMetric.Mode.First),
                ),
            iterations = 5,
            startupMode = StartupMode.COLD,
            setupBlock = {
                openLibrary()
                device.findOrFail("theme_row_$PAPER").click()
                device.waitForIdle()
            },
        ) {
            startHome()
            device.findOrFail(Strata.ALL_APPS)
        }

    private companion object {
        const val TARGET = "app.strata.launcher.benchmark"
        const val PILL_MENU = "home_search_pill_menu"
        const val MENU_THEMES = "home_menu_themes"
        const val LIBRARY = "theme_library"
        const val NEW_THEME = "theme_new"
        const val EDITOR_PREVIEW = "editor_preview"
        const val EDITOR_SCROLL = "editor_scroll"
        const val PREVIEW_DARK = "editor_preview_dark"
        const val ACCENT_SWATCH = "editor_swatch_colors_light_accent"
        const val ACCENT_HUE = "editor_slider_colors_light_accent_hue"
        const val APPLY_SECTION = "strata.themeApply"
        const val PARSE_SECTION = "strata.themeParse"
        const val PAPER = "c5017d5b-b2b0-48ff-83ed-33f77fd14754"
        const val STRATA_DEFAULT = "a413ab93-de91-4614-8c5c-cfbe6d79231b"
        const val DRAG_STEPS = 500
        const val TIMEOUT_MS = 5_000L
    }
}
