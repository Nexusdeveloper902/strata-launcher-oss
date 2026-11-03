package app.strata.launcher.search

import android.os.ParcelFileDescriptor
import android.util.Log
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
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.feature.onboarding.OnboardingTestTags
import app.strata.launcher.feature.search.SearchTestTags
import app.strata.launcher.home.HomeActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Search on a device or emulator (level I): T-SEARCH-02 (hardware key) and T-SEARCH-10 (Back semantics).
 * Not run in this environment (no emulator); compiled by `assembleDebugAndroidTest`. The predictive-back
 * cancel half of T-SEARCH-10 needs a gesture-navigation device and is checked manually.
 */
@RunWith(AndroidJUnit4::class)
class SearchDeviceTest {
    val compose = createAndroidComposeRule<HomeActivity>()

    /** The device is prepared (home app, system error dialogs) before the activity starts. */
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
        // Key events and the IME go to the focused window; wait until it is ours. A system error dialog
        // (e.g. "Pixel Launcher isn't responding" on a loaded emulator) holds focus over every app: close it.
        // When focus never arrives, say who holds it instead of timing out blind.
        try {
            compose.waitUntil(timeoutMillis = 30_000) {
                hasWindowFocus().also { focused -> if (!focused) closeSystemErrorDialog(device) }
            }
        } catch (e: ComposeTimeoutException) {
            val focus = focusReport()
            Log.e(TAG, "Home window never focused. $focus")
            throw AssertionError("Home window never focused. $focus", e)
        }
    }

    private fun hasWindowFocus(): Boolean {
        var focused = false
        compose.activityRule.scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }
        return focused
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
        compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
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
        compose.waitUntil(timeoutMillis = 3_000) { !searchOpen() }
    }
}

private const val TAG = "SearchDeviceTest"

/**
 * Runs [command] as the shell user. This is a plain exec, not a shell: no pipes, quoting or redirection,
 * so filter the output in Kotlin.
 */
private fun shell(command: String): String =
    ParcelFileDescriptor
        .AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
        .use { it.readBytes().decodeToString().trim() }

/** Who holds input focus, from `dumpsys window` (focused window and app) and the resumed activities. */
private fun focusReport(): String {
    val window =
        shell("dumpsys window").lineSequence().map(String::trim).filter {
            it.startsWith("mCurrentFocus") || it.startsWith("mFocusedApp") || it.startsWith("mFocusedWindow")
        }
    val activities =
        shell("dumpsys activity activities").lineSequence().map(String::trim).filter {
            it.startsWith("mResumedActivity") || it.startsWith("topResumedActivity") || it.startsWith("ResumedActivity")
        }
    return (window + activities).distinct().joinToString(" | ")
}

/**
 * Closes a system "isn't responding" / "has stopped" dialog if one is showing. Such a dialog is a system window
 * that takes input focus from every app until dismissed; on a freshly booted, heavily loaded emulator the
 * system launcher (behind our activity) can trigger one. Returns whether a dialog was closed.
 */
private fun closeSystemErrorDialog(device: UiDevice): Boolean {
    val button = device.findObject(By.res("android", "aerr_close")) ?: device.findObject(By.res("android", "aerr_wait"))
    if (button == null) return false
    Log.w(TAG, "Closing system error dialog: " + focusReport())
    button.click()
    return true
}

/**
 * Device state for these tests, set before the activity starts and restored afterwards:
 * - Strata is the default home (best effort: `cmd package set-home-activity` is absent on some API levels), so
 *   the system launcher does not compete as HOME while ours runs.
 * - System error dialogs are suppressed (`hide_error_dialogs`, as on ATD images): an unresponsive background
 *   app is then killed instead of a dialog taking input focus. Any dialog already showing is closed.
 */
private class DeviceStateRule : TestRule {
    private val component = "app.strata.launcher/app.strata.launcher.home.HomeActivity"

    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                val previousHome =
                    Regex("""ComponentInfo\{([^}]+)\}""")
                        .find(shell("cmd shortcut get-default-launcher"))
                        ?.groupValues
                        ?.get(1)
                Log.i(TAG, "set-home-activity: " + shell("cmd package set-home-activity $component"))
                val previousHideErrorDialogs = shell("settings get global hide_error_dialogs")
                shell("settings put global hide_error_dialogs 1")
                closeSystemErrorDialog(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))
                try {
                    base.evaluate()
                } finally {
                    if (previousHideErrorDialogs == "null" || previousHideErrorDialogs.isEmpty()) {
                        shell("settings delete global hide_error_dialogs")
                    } else {
                        shell("settings put global hide_error_dialogs $previousHideErrorDialogs")
                    }
                    if (previousHome != null && !previousHome.startsWith("app.strata.launcher/")) {
                        shell("cmd package set-home-activity $previousHome")
                    }
                }
            }
        }
}

private fun ComposeTestRule.count(tag: String): Int = onAllNodesWithTag(tag).fetchSemanticsNodes().size
