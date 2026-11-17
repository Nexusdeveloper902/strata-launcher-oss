package app.strata.launcher.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import app.strata.launcher.a11y.ContrastTest
import app.strata.launcher.a11y.KeyboardFixture
import app.strata.launcher.core.theme.model.Alignment
import app.strata.launcher.core.theme.model.HourFormat
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import app.strata.launcher.core.theme.store.ThemePair
import app.strata.launcher.core.theme.store.ThemeState
import app.strata.launcher.feature.home.HomeTestTags
import com.google.common.truth.Truth.assertWithMessage
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
import kotlin.math.abs

/**
 * T-THEME-56 (FB-007, owner report: "the clock that is supposed to be centred is off-centre in some themes"). With
 * `clock.alignment: center`, the inked pixels of the clock time and of the date are centred on the screen within
 * ±1 dp, for every built-in preset, in 12 h and 24 h, with seconds off and on, in LTR and RTL. Pixels come from
 * Robolectric's native graphics; ink is every pixel that differs from its row's most frequent colour, across the full
 * width of the window except the alphabet index strip and, for the time, the day-period node (it hangs beside the time).
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

    private fun centred(theme: ResolvedTheme): ResolvedTheme =
        theme.copy(
            clock =
                theme.clock.copy(
                    alignment = Alignment.CENTER,
                    hourFormat = if (h24) HourFormat.H24 else HourFormat.H12,
                    showSeconds = seconds,
                    visible = true,
                    dateVisible = true,
                ),
        )

    private fun start(): HomeActivity {
        if (rtl) RuntimeEnvironment.setQualifiers("+ldrtl")
        val base = ContrastTest.presetState(preset)
        val state = ThemeState(themes = ThemePair(centred(base.themes.light), centred(base.themes.dark)), loaded = true)
        KeyboardFixture.graph(theme = state).install()
        val intent = KeyboardFixture.homeIntent(ApplicationProvider.getApplicationContext())
        val activity =
            Robolectric
                .buildActivity(HomeActivity::class.java, intent)
                .setup()
                .get()
        idle()
        return activity
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    @Test
    fun clockAndDateAreCentred() {
        val activity = start()
        assertCentred(activity, label = "")
    }

    /** The same with a display cutout on one side only (a punch-hole phone in landscape-like insets). */
    @Test
    fun centredWithAsymmetricCutout() {
        // One fixture per preset is enough: the insets do not depend on the hour format or direction.
        if (h24 || seconds || rtl) return
        val activity = start()
        val decor = activity.window.decorView
        val insets =
            WindowInsetsCompat
                .Builder()
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(CUTOUT_PX, 0, 0, 0))
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, STATUS_PX, 0, NAV_PX))
                .build()
        compose.runOnIdle { ViewCompat.dispatchApplyWindowInsets(decor, insets) }
        idle()
        assertCentred(activity, label = " (cutout ${CUTOUT_PX}px on the left)")
    }

    private fun assertCentred(
        activity: HomeActivity,
        label: String,
    ) {
        val window = activity.window.decorView
        val density = activity.resources.displayMetrics.density
        val shot = draw(window)
        val nodes = nodes(window)
        val index = nodes.firstOrNull { it.tag() == HomeTestTags.HOME_INDEX }?.boundsInRoot
        val period = nodes.firstOrNull { it.tag() == HomeTestTags.CLOCK_PERIOD }?.boundsInRoot
        val hasTime = nodes.any { it.tag() == HomeTestTags.CLOCK_TIME }
        val timeTag = if (hasTime) HomeTestTags.CLOCK_TIME else HomeTestTags.CLOCK
        val where = "$preset h24=$h24 seconds=$seconds rtl=$rtl$label"
        val targets = listOf(timeTag to listOfNotNull(index, period), HomeTestTags.DATE to listOfNotNull(index))
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

    private fun nodes(view: View): List<SemanticsNode> {
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
        if (view !is android.view.ViewGroup) return emptyList()
        return (0 until view.childCount).flatMap { owners(view.getChildAt(it)) }
    }

    private fun SemanticsNode.tag(): String? =
        config.getOrNull(SemanticsProperties.TestTag)

    companion object {
        private const val TOLERANCE_DP = 1f
        private const val INK_THRESHOLD = 96
        private const val CUTOUT_PX = 120
        private const val STATUS_PX = 72
        private const val NAV_PX = 48

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-h24={1}-s={2}-rtl={3}")
        fun parameters(): List<Array<Any>> {
            val dir = listOf(File("../spec/sample-themes"), File("spec/sample-themes")).first { it.isDirectory }
            val presets =
                listOf("strata-default") +
                    dir
                        .listFiles { f -> f.name.endsWith(".strata-theme.json") }!!
                        .map { it.name.removeSuffix(".strata-theme.json") }
                        .sorted()
            val flags = listOf(false, true)
            return presets.flatMap { p ->
                flags.flatMap { h -> flags.flatMap { s -> flags.map { r -> arrayOf<Any>(p, h, s, r) } } }
            }
        }
    }
}
