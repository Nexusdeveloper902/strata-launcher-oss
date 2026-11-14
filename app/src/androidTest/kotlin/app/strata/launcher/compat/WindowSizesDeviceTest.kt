package app.strata.launcher.compat

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.coverForeignActivities
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.passOnboarding
import app.strata.launcher.shell
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * T-COMPAT-06 (NFR-COMPAT-03), the automatable part on every GMD phone: the display is resized in 20 steps from
 * 220×300 dp to 1920×1200 dp with `wm density 160` (1 dp = 1 px, so the large end stays a cheap 1920×1200 surface) and
 * `wm size`. HomeActivity handles size and density changes in place (configChanges, ANDROID_PLATFORM §22): after every
 * step home is still composed with its root and pill (no crash, no blank state). Split-screen and free-form windows are
 * T-COMPAT-07 on the API 36 desktop/foldable emulators and RD-5 (manual pass sheet). The display's own size and density
 * overrides (e.g. Screen zoom) are recorded first and restored exactly; only values that had no override are reset.
 */
@RunWith(AndroidJUnit4::class)
class WindowSizesDeviceTest {
    val compose = createAndroidComposeRule<HomeActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(DeviceStateRule()).around(compose)

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** The display overrides found before the test (e.g. Samsung Screen zoom), restored exactly afterwards. */
    private var savedSize: String? = null
    private var savedDensity: String? = null

    @Before
    fun ready() {
        savedSize = override(shell("wm size"), "Override size:")
        savedDensity = override(shell("wm density"), "Override density:")
        compose.waitForIdle()
        compose.passOnboarding()
        compose.waitUntil(10_000) { count(HomeTestTags.ROOT) > 0 }
    }

    @After
    fun tearDown() {
        restoreDisplay()
        var owned: HomeActivity? = null
        runCatching { compose.activityRule.scenario.onActivity { owned = it } }
        compose.coverForeignActivities(HomeActivity::class.java, owned)
    }

    @Test
    fun tCompat06_resizeIn20Steps() {
        shell("wm density $MDPI")
        val failures = mutableListOf<String>()
        for (step in 0 until STEPS) {
            val w = lerp(MIN_W, MAX_W, step)
            val h = lerp(MIN_H, MAX_H, step)
            shell("wm size ${w}x$h")
            device.waitForIdle()
            val shown =
                runCatching {
                    compose.waitUntil(STEP_TIMEOUT_MS) { count(HomeTestTags.ROOT) > 0 && count(HomeTestTags.PILL) > 0 }
                }.isSuccess
            if (!shown) failures += "${w}x$h dp: home root or pill missing"
        }
        assertWithMessage("home lost at some window sizes").that(failures).isEmpty()
    }

    private fun lerp(
        from: Int,
        to: Int,
        step: Int,
    ) = from + (to - from) * step / (STEPS - 1)

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    /** Puts back the owner's own overrides; resets only what had no override before. */
    private fun restoreDisplay() {
        shell(savedSize?.let { "wm size $it" } ?: "wm size reset")
        shell(savedDensity?.let { "wm density $it" } ?: "wm density reset")
        device.waitForIdle()
    }

    /** `wm size` / `wm density` print `Physical …: v` and, when overridden, `Override …: v`. */
    private fun override(
        output: String,
        label: String,
    ): String? =
        output
            .lines()
            .map(String::trim)
            .firstOrNull { it.startsWith(label) }
            ?.removePrefix(label)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private companion object {
        const val STEPS = 20
        const val MDPI = 160
        const val MIN_W = 220
        const val MIN_H = 300
        const val MAX_W = 1920
        const val MAX_H = 1200
        const val STEP_TIMEOUT_MS = 10_000L
    }
}
