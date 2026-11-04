package app.strata.launcher.search

import android.view.KeyEvent
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.awaitWindowFocus
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.feature.onboarding.OnboardingTestTags
import app.strata.launcher.feature.search.SearchTestTags
import app.strata.launcher.focusReport
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.openAllApps
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Search on a device or emulator (level I): T-SEARCH-02 (hardware key) and T-SEARCH-10 (Back semantics).
 * Not run in this environment (no emulator); compiled by `assembleDebugAndroidTest`. The predictive-back
 * cancel half of T-SEARCH-10 needs a gesture-navigation device and is checked manually.
 */
@RunWith(AndroidJUnit4::class)
class SearchDeviceTest {
    val compose = createAndroidComposeRule<HomeActivity>()

    /** The device is prepared (home app, system error dialogs, animations off) before the activity starts. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(DeviceStateRule()).around(compose)

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun skipOnboarding() {
        compose.waitForIdle()
        if (compose.count(OnboardingTestTags.CONTINUE) > 0) {
            compose.onNodeWithTag(OnboardingTestTags.CONTINUE).performClick()
        }
        compose.waitUntil(timeoutMillis = 10_000) { compose.count(HomeTestTags.ALL_APPS) > 0 }
        // Key events and the IME go to the focused window; a system error dialog can hold focus (DeviceState.kt).
        compose.awaitWindowFocus(device)
    }

    private fun imeVisible(): Boolean {
        var visible = false
        compose.activityRule.scenario.onActivity { activity ->
            visible =
                ViewCompat
                    .getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    private fun searchOpen() = compose.count(SearchTestTags.LAYER) > 0

    /** T-SEARCH-02 (I): "m" typed on home opens search with query "m" and results. */
    @Test
    fun tSearch02_hardwareKeyOpensSearch() {
        device.pressKeyCode(KeyEvent.KEYCODE_M)
        compose.waitUntil(timeoutMillis = 5_000) { searchOpen() }
        compose.onNodeWithTag(SearchTestTags.FIELD).assert(hasText("m"))
    }

    /** T-SEARCH-10 (I): Back hides the IME first, then closes; with an empty query one Back closes. */
    @Test
    fun tSearch10_backSemantics() {
        compose.openAllApps()
        compose.onNodeWithTag(AppListTestTags.SEARCH_FIELD).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { searchOpen() }
        compose.onNodeWithTag(SearchTestTags.FIELD).performTextInput("ma")
        compose.waitUntil(timeoutMillis = 3_000) { imeVisible() }

        device.pressBack()
        compose.waitUntil(timeoutMillis = 3_000) { !imeVisible() }
        compose.waitForIdle()
        check(searchOpen()) { "first Back must only hide the IME" }

        device.pressBack()
        compose.waitUntil(timeoutMillis = 3_000) { !searchOpen() }
        check(compose.count(AppListTestTags.LIST) > 0) { "search returns to its origin (list)" }

        compose.onNodeWithTag(AppListTestTags.SEARCH_FIELD).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { searchOpen() && imeVisible() }
        device.pressBack()
        // The close follows the IME hide, so wait for the window to report the IME hidden (system latency), then give
        // search 3 s to react. On a starved emulator the hide alone can take seconds: PR #8 run 41655333918 (API 36)
        // logged the Back at once (ImeBackAnimationController hide request), pressBack returning after 1.1 s, and
        // the hide animation at ~1 s per frame that never reported its end (ImeTracker STATUS_TIMEOUT 10 s later).
        val imeHidden = runCatching { compose.waitUntil(timeoutMillis = 10_000) { !imeVisible() } }.isSuccess
        try {
            compose.waitUntil(timeoutMillis = 3_000) { !searchOpen() }
        } catch (e: ComposeTimeoutException) {
            // Say what state the one-Back close saw (CI run 41576277117, API 26: search stayed open, cause unknown).
            val state =
                "imeHiddenWithin10s=$imeHidden imeVisible=${imeVisible()} searchOpen=${searchOpen()} focus=${focusReport()}"
            throw AssertionError("One Back with an empty query did not close search: $state", e)
        }
    }
}

private fun ComposeTestRule.count(tag: String): Int = onAllNodesWithTag(tag).fetchSemanticsNodes().size
