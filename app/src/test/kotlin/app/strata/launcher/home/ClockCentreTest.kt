package app.strata.launcher.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import app.strata.launcher.a11y.ContrastTest
import app.strata.launcher.a11y.KeyboardFixture
import app.strata.launcher.core.theme.model.Alignment
import app.strata.launcher.core.theme.model.ClockStyle
import app.strata.launcher.core.theme.model.HourFormat
import app.strata.launcher.core.theme.resolve.ResolvedClock
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import app.strata.launcher.core.theme.store.ThemePair
import app.strata.launcher.core.theme.store.ThemeState
import app.strata.launcher.feature.home.ClockFormat
import app.strata.launcher.feature.home.HomeTestTags
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * T-THEME-56 (FB-007, owner report: "the clock that is supposed to be centred is off-centre in some themes"). With
 * `clock.alignment: center`, the inked pixels of the clock time and of the date are centred on the screen within
 * ±1 dp, for every built-in preset, in 12 h and 24 h, with seconds off and on, in LTR and RTL: 6 presets × 8 = 48
 * cases. Pixels come from Robolectric's native graphics; ink is every pixel that differs from its row's most frequent
 * colour, across the full width of the window except the alphabet index strip and, for the time, the day-period node
 * (it hangs beside the time). The clock shows a fixed time ([ClockScreen.FIXED_MILLIS], UTC), so a failure reproduces.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClockCentreTest(
    private val preset: String,
    private val h24: Boolean,
    private val seconds: Boolean,
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
    fun clockAndDateAreCentred() {
        val activity = screen.start(preset, rtl) { it.copy(hourFormat = hourFormat(h24), showSeconds = seconds) }
        screen.assertFixedTime(activity, h24, seconds)
        screen.assertCentred(activity, "$preset h24=$h24 seconds=$seconds rtl=$rtl")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-h24={1}-s={2}-rtl={3}")
        fun parameters(): List<Array<Any>> {
            val flags = listOf(false, true)
            return ClockScreen.presets().flatMap { p ->
                flags.flatMap { h -> flags.flatMap { s -> flags.map { r -> arrayOf<Any>(p, h, s, r) } } }
            }
        }
    }
}

/**
 * T-THEME-56, cutout fixture: a display cutout on the left only (120 px) and system bars, per preset (6 cases, 12 h
 * with seconds, LTR). The test first checks that the inset reached the Compose layout (the favourites start after it).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClockCentreCutoutTest(
    private val preset: String,
) {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val screen = ClockScreen(compose)

    @Before
    fun fixTime() = screen.fixTime()

    @After
    fun restoreTime() = screen.restoreTime()

    @Test
    fun centredWithAsymmetricCutout() {
        val activity = screen.start(preset, rtl = false) { it.copy(hourFormat = HourFormat.H12, showSeconds = true) }
        val insets =
            WindowInsetsCompat
                .Builder()
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(CUTOUT_PX, 0, 0, 0))
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, STATUS_PX, 0, NAV_PX))
                .build()
        compose.runOnIdle { ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, insets) }
        screen.idle()
        val favourites = screen.nodes(activity.window.decorView).first { it.tag() == HomeTestTags.FAVORITES }
        assertWithMessage("$preset: the cutout inset did not reach the Compose layout (favourites start)")
            .that(favourites.boundsInRoot.left)
            .isAtLeast(CUTOUT_PX.toFloat())
        screen.assertFixedTime(activity, h24 = false, seconds = true)
        screen.assertCentred(activity, "$preset 12 h seconds, cutout ${CUTOUT_PX}px on the left")
    }

    companion object {
        private const val CUTOUT_PX = 120
        private const val STATUS_PX = 72
        private const val NAV_PX = 48

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = ClockScreen.presets().map { arrayOf<Any>(it) }
    }
}

/**
 * T-THEME-56, fit: on a 360 dp phone, centred, 12 h with seconds at the largest `sizeSp` (128), the time and the day
 * period stay inside the header's span (window minus the larger side inset and the 16 dp header padding, the alphabet
 * index included) in LTR and RTL.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClockFitTest(
    private val preset: String,
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
    fun periodStaysInsideTheSpan() {
        val activity =
            screen.start(preset, rtl) {
                it.copy(
                    style = ClockStyle.NUMERIC,
                    hourFormat = HourFormat.H12,
                    showSeconds = true,
                    sizeSp = MAX_SIZE_SP,
                )
            }
        screen.assertFixedTime(activity, h24 = false, seconds = true)
        val window = activity.window.decorView
        val density = activity.resources.displayMetrics.density
        val nodes = screen.nodes(window)
        val index = nodes.firstOrNull { it.tag() == HomeTestTags.HOME_INDEX }?.boundsInRoot
        // The header keeps the larger side (index reserve or nothing) on both sides, plus its own 16 dp padding.
        val side = (if (index != null) INDEX_RESERVE_DP else 0f) * density + HEADER_PADDING_DP * density
        val spanLeft = side
        val spanRight = window.width - side
        // What is drawn (time and period together) stays inside the span; the period never reaches the index.
        val clock = nodes.first { it.tag() == HomeTestTags.CLOCK }.boundsInRoot
        val ink = screen.ink(window, clock, listOfNotNull(index))
        assertWithMessage("$preset rtl=$rtl: no clock ink").that(ink).isNotNull()
        val (left, right) = ink!!
        assertWithMessage("$preset rtl=$rtl: clock ink [$left, $right] outside the span [$spanLeft, $spanRight]")
            .that(left >= spanLeft - 1 && right <= spanRight + 1)
            .isTrue()
        val period = nodes.first { it.tag() == HomeTestTags.CLOCK_PERIOD }.boundsInRoot
        if (index != null) {
            assertWithMessage("$preset rtl=$rtl: the period overlaps the index").that(period.overlaps(index)).isFalse()
        }
    }

    companion object {
        private const val MAX_SIZE_SP = 128
        private const val INDEX_RESERVE_DP = 48f
        private const val HEADER_PADDING_DP = 16f

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-rtl={1}")
        fun parameters(): List<Array<Any>> =
            ClockScreen.presets().flatMap { p -> listOf(false, true).map { r -> arrayOf<Any>(p, r) } }
    }
}

private fun hourFormat(h24: Boolean) = if (h24) HourFormat.H24 else HourFormat.H12

private fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)

/** Home with a centred clock under a fixed time, and the pixel and semantics reads of the clock tests. */
internal class ClockScreen(
    private val compose: ComposeTestRule,
) {
    private var zone: TimeZone? = null

    fun fixTime() {
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    fun restoreTime() {
        zone?.let { TimeZone.setDefault(it) }
    }

    fun start(
        preset: String,
        rtl: Boolean,
        clock: (ResolvedClock) -> ResolvedClock,
    ): HomeActivity {
        if (rtl) RuntimeEnvironment.setQualifiers("+ldrtl")

        fun centred(theme: ResolvedTheme): ResolvedTheme =
            theme.copy(
                clock =
                    clock(theme.clock).copy(alignment = Alignment.CENTER, visible = true, dateVisible = true),
            )
        val base = ContrastTest.presetState(preset)
        val state = ThemeState(themes = ThemePair(centred(base.themes.light), centred(base.themes.dark)), loaded = true)
        KeyboardFixture
            .graph(theme = state)
            .apply { timeTicks.fixedNowMillis = FIXED_MILLIS }
            .install()
        val intent = KeyboardFixture.homeIntent(ApplicationProvider.getApplicationContext())
        val activity =
            Robolectric
                .buildActivity(HomeActivity::class.java, intent)
                .setup()
                .get()
        idle()
        return activity
    }

    fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    /** The clock shows [FIXED_MILLIS]: its spoken text is the fixed time's. */
    fun assertFixedTime(
        activity: HomeActivity,
        h24: Boolean,
        seconds: Boolean,
    ) {
        val clock = nodes(activity.window.decorView).first { it.tag() == HomeTestTags.CLOCK }
        val spoken = clock.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("")
        val expected = ClockFormat.format(FIXED_MILLIS, h24, seconds, Locale.getDefault(), TimeZone.getDefault()).full
        assertWithMessage("the clock does not show the fixed time").that(spoken).isEqualTo(expected)
    }

    fun assertCentred(
        activity: HomeActivity,
        where: String,
    ) {
        val window = activity.window.decorView
        val density = activity.resources.displayMetrics.density
        val shot = draw(window)
        val nodes = nodes(window)
        val index = nodes.firstOrNull { it.tag() == HomeTestTags.HOME_INDEX }?.boundsInRoot
        val period = nodes.firstOrNull { it.tag() == HomeTestTags.CLOCK_PERIOD }?.boundsInRoot
        val time = nodes.firstOrNull { it.tag() == HomeTestTags.CLOCK_TIME }?.boundsInRoot
        val box = nodes.first { it.tag() == HomeTestTags.CLOCK }.boundsInRoot
        // The time is centred with the period's width reserved on both sides. When that does not fit, the layout falls
        // back to time + period side by side (never overflowing), and then the two together are what is centred.
        val reserved =
            time != null && period != null && abs(box.width - (time.width + 2 * period.width)) <= 1f
        val timeTarget =
            when {
                time == null -> HomeTestTags.CLOCK to listOfNotNull(index)
                period == null || reserved -> HomeTestTags.CLOCK_TIME to listOfNotNull(index, period)
                else -> HomeTestTags.CLOCK to listOfNotNull(index)
            }
        val targets = listOf(timeTarget, HomeTestTags.DATE to listOfNotNull(index))
        for ((tag, skip) in targets) {
            val node = nodes.firstOrNull { it.tag() == tag }
            assertWithMessage("$where: $tag not on screen").that(node).isNotNull()
            val ink = shot.inkColumns(node!!.boundsInRoot, skip)
            assertWithMessage("$where: no ink for $tag").that(ink).isNotNull()
            val (left, right) = ink!!
            val offsetDp = ((left + right + 1) / 2f - shot.width / 2f) / density
            val off = "%.2f".format(offsetDp)
            assertWithMessage("$where: $tag ink [$left, $right] of ${shot.width} px is off-centre by $off dp")
                .that(abs(offsetDp))
                .isAtMost(TOLERANCE_DP)
        }
    }

    /** First and last inked column in the rows of [band] (columns inside [skip] ignored). */
    fun ink(
        view: View,
        band: Rect,
        skip: List<Rect>,
    ): Pair<Int, Int>? = draw(view).inkColumns(band, skip)

    fun nodes(view: View): List<SemanticsNode> {
        compose.onAllNodesWithTag(HomeTestTags.ROOT).fetchSemanticsNodes()
        val out = mutableListOf<SemanticsNode>()
        compose.runOnIdle {
            fun walk(n: SemanticsNode) {
                out += n
                n.children.forEach(::walk)
            }
            owners(view).forEach { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
        }
        return out
    }

    private fun owners(view: View): List<ViewRootForTest> {
        if (view is ViewRootForTest) return listOf(view)
        if (view !is ViewGroup) return emptyList()
        return (0 until view.childCount).flatMap { owners(view.getChildAt(it)) }
    }

    private fun draw(view: View): Shot {
        lateinit var shot: Shot
        compose.runOnIdle {
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            shot = Shot(pixels, image.width, image.height)
        }
        return shot
    }

    private class Shot(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
    ) {
        /** First and last column with ink in the rows of [band], ignoring the columns inside [skip]; null if none. */
        fun inkColumns(
            band: Rect,
            skip: List<Rect>,
        ): Pair<Int, Int>? {
            var left = Int.MAX_VALUE
            var right = -1
            val top = band.top.toInt().coerceIn(0, height - 1)
            val bottom = band.bottom.toInt().coerceIn(0, height)
            for (y in top until bottom) {
                val row = IntArray(width) { pixels[y * width + it] }
                val background =
                    row
                        .toList()
                        .groupingBy { it }
                        .eachCount()
                        .maxBy { it.value }
                        .key
                for (x in 0 until width) {
                    if (skip.any { x >= it.left && x < it.right && y >= it.top && y < it.bottom }) continue
                    if (distance(row[x], background) > INK_THRESHOLD) {
                        left = minOf(left, x)
                        right = maxOf(right, x)
                    }
                }
            }
            return if (right < 0) null else left to right
        }

        private fun distance(
            a: Int,
            b: Int,
        ): Int =
            abs(Color.alpha(a) - Color.alpha(b)) + abs(Color.red(a) - Color.red(b)) +
                abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
    }

    companion object {
        /** 2026-11-17 21:11:41 UTC: "1" digits (the widest side bearings) and a PM period. */
        const val FIXED_MILLIS = 1_794_949_901_000L
        private const val TOLERANCE_DP = 1f
        private const val INK_THRESHOLD = 96

        /** Strata Default and every `spec/sample-themes/` preset. */
        fun presets(): List<String> {
            val dir = listOf(File("../spec/sample-themes"), File("spec/sample-themes")).first { it.isDirectory }
            return listOf("strata-default") +
                dir
                    .listFiles { f -> f.name.endsWith(".strata-theme.json") }!!
                    .map { it.name.removeSuffix(".strata-theme.json") }
                    .sorted()
        }
    }
}
