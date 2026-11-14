package app.strata.launcher.compat

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.awaitWindowFocus
import app.strata.launcher.core.ui.AlphabetIndexTestTags
import app.strata.launcher.coverForeignActivities
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.openAllApps
import app.strata.launcher.passOnboarding
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * T-COMPAT-10 (NFR-COMPAT-01), the I half on every GMD phone (API 26, 34, 36): home is edge-to-edge, and its content
 * respects the system bars. The clock starts below the status bar and the search pill ends above the navigation bar;
 * the list's alphabet index ends above the navigation bar too. The cutout and API 30/35 emulators and the screenshots
 * (S half) are in the nightly matrix and the manual pass. Bounds are in window pixels, the insets those of the window.
 */
@RunWith(AndroidJUnit4::class)
class InsetsDeviceTest {
    val compose = createAndroidComposeRule<HomeActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(DeviceStateRule()).around(compose)

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun ready() {
        compose.waitForIdle()
        compose.passOnboarding()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(HomeTestTags.PILL).fetchSemanticsNodes().isNotEmpty() }
        compose.awaitWindowFocus(device)
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        var owned: HomeActivity? = null
        runCatching { compose.activityRule.scenario.onActivity { owned = it } }
        compose.coverForeignActivities(HomeActivity::class.java, owned)
    }

    private class Bars(
        val top: Int,
        val bottom: Int,
        val height: Int,
    )

    private fun bars(): Bars {
        var bars = Bars(0, 0, 0)
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            val insets = ViewCompat.getRootWindowInsets(view)
            checkNotNull(insets) { "no root window insets on the home window" }
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            bars = Bars(sys.top, sys.bottom, view.height)
        }
        // GMD phones (and every phone) have a status bar at rest: a zero top would make the clock check vacuous.
        assertWithMessage("status bar inset (top) on the home window").that(bars.top).isGreaterThan(0)
        assertWithMessage("window height").that(bars.height).isGreaterThan(0)
        return bars
    }

    /** The union of every node with [tag] (a layer below the visible one may compose the same element). */
    private fun bounds(tag: String): Rect =
        compose
            .onAllNodesWithTag(tag)
            .fetchSemanticsNodes()
            .map { it.boundsInWindow }
            .reduce {
                a,
                b,
                ->
                Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
            }

    @Test
    fun tCompat10_homeRespectsSystemBars() {
        val b = bars()
        val clock = bounds(HomeTestTags.CLOCK)
        val pill = bounds(HomeTestTags.PILL)
        assertWithMessage(
            "clock top ${clock.top} under the status bar (${b.top})",
        ).that(clock.top).isAtLeast(b.top.toFloat())
        assertWithMessage("pill bottom ${pill.bottom} over the navigation bar (${b.height - b.bottom})")
            .that(pill.bottom)
            .isAtMost((b.height - b.bottom).toFloat() + TOLERANCE_PX)
    }

    @Test
    fun tCompat10_listRespectsNavigationBar() {
        compose.openAllApps()
        val b = bars()
        val index = bounds(AlphabetIndexTestTags.INDEX)
        assertWithMessage("alphabet index bottom ${index.bottom} over the navigation bar (${b.height - b.bottom})")
            .that(index.bottom)
            .isAtMost((b.height - b.bottom).toFloat() + TOLERANCE_PX)
    }

    private companion object {
        /** Rounding between Compose's float bounds and the integer insets. */
        const val TOLERANCE_PX = 1f
    }
}
