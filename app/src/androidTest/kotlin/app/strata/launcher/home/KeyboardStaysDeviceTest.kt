package app.strata.launcher.home

import android.os.SystemClock
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.strata.launcher.DEVICE_TIMEOUT_MS
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.awaitWindowFocus
import app.strata.launcher.feature.home.FavEditTestTags
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.SheetTestTags
import app.strata.launcher.passOnboarding
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * T-HOME-14 (I): the Edit favourites "Add apps" search keeps the soft keyboard on a real IME (the owner's Galaxy A56
 * report: it closed by itself). Not run in this environment (no emulator); compiled by `assembleDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardStaysDeviceTest {
    val compose = createAndroidComposeRule<HomeActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(DeviceStateRule()).around(compose)

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun skipOnboarding() {
        compose.waitForIdle()
        compose.passOnboarding()
        compose.waitUntil(DEVICE_TIMEOUT_MS) { compose.count(HomeTestTags.ALL_APPS) > 0 }
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

    @Test
    fun tHome14_favouritesPickerKeepsKeyboard() {
        compose.onNodeWithTag(HomeTestTags.PILL_MENU).performClick()
        compose.waitUntil(DEVICE_TIMEOUT_MS) { compose.count(SheetTestTags.MENU_EDIT) > 0 }
        compose.onNodeWithTag(SheetTestTags.MENU_EDIT).performClick()
        compose.waitUntil(DEVICE_TIMEOUT_MS) { compose.count(FavEditTestTags.ADD_APPS) > 0 }
        compose.onNodeWithTag(FavEditTestTags.ADD_APPS).performClick()
        compose.waitUntil(DEVICE_TIMEOUT_MS) { compose.count(FavEditTestTags.PICKER_SEARCH) > 0 }
        compose.onNodeWithTag(FavEditTestTags.PICKER_SEARCH).performClick()
        compose.waitUntil(DEVICE_TIMEOUT_MS) { imeVisible() }
        // Settle past the show animation and the guard's reaction; the keyboard must still be up.
        val until = SystemClock.uptimeMillis() + SETTLE_MS
        while (SystemClock.uptimeMillis() < until) {
            compose.waitForIdle()
            check(imeVisible()) { "the keyboard of the favourites picker closed by itself" }
            SystemClock.sleep(POLL_MS)
        }
        compose.onNodeWithTag(FavEditTestTags.PICKER_SEARCH).assertIsFocused()
    }

    private companion object {
        const val SETTLE_MS = 2_000L
        const val POLL_MS = 100L
    }
}

private fun ComposeTestRule.count(tag: String): Int = onAllNodesWithTag(tag).fetchSemanticsNodes().size
