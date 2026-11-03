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
        // The close follows the IME hide, so wait for the window to report the IME hidden (system latency), then give
        // search 3 s to react. On a starved emulator the hide alone can take seconds: PR #8 run 41655333918 (API 36)
        // logged the Back at once (ImeBackAnimationController hide request), pressBack returning after 1.1 s, and
        // the hide animation at ~1 s per frame that never reported its end (ImeTracker STATUS_TIMEOUT 10 s later).
        val imeHidden = runCatching { compose.waitUntil(timeoutMillis = 10_000) { !imeVisible() } }.isSuccess
        try {
            compose.waitUntil(timeoutMillis = 3_000) { !searchOpen() }
        } catch (e: ComposeTimeoutException) {
            val state =
                "imeHiddenWithin10s=$imeHidden imeVisible=${imeVisible()} searchOpen=${searchOpen()} focus=${focusReport()}"
            throw AssertionError("One Back with an empty query did not close search: $state", e)
        }
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
 *
 * For System UI the dialog is answered "Wait", never "Close app": killing System UI restarts it with the
 * keyguard up, which stops our activity, and the restarted, loaded system can then not show the IME
 * (docs/impl-notes/M2.md item 15, CI run 41664919322). Other apps (e.g. the system launcher) are closed.
 */
private fun closeSystemErrorDialog(device: UiDevice): Boolean {
    val close = device.findObject(By.res("android", "aerr_close"))
    val wait = device.findObject(By.res("android", "aerr_wait"))
    if (close == null && wait == null) return false
    val focus = focusReport()
    val systemUi = focus.contains("Not Responding: com.android.systemui")
    val button = if (systemUi) wait ?: close else close ?: wait
    Log.w(TAG, "Answering system error dialog with " + (if (button === wait) "Wait" else "Close") + ": " + focus)
    button?.click()
    return true
}

/**
 * Device state for these tests, set before the activity starts and restored afterwards:
 * - Strata is the default home (best effort: `cmd package set-home-activity` is absent on some API levels), so
 *   the system launcher does not compete as HOME while ours runs.
 * - System error dialogs are suppressed (`hide_error_dialogs`, as on ATD images): an unresponsive background
 *   app is then killed instead of a dialog taking input focus. Any dialog already showing is closed.
 * - Window, transition and animator scales are 0 (the usual instrumented-test setting). On the software-rendered
 *   CI emulators an IME insets animation can run for seconds or hit the 10 s timeout (CI runs 41571533617 and
 *   41667894220), which makes Back timing depend on emulator load. The close logic keys off the IME target state
 *   either way (docs/impl-notes/M2.md items 16, 17); this only removes the load-dependent animation time.
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
                val previousScales = animationScales.associateWith { shell("settings get global $it") }
                animationScales.forEach { shell("settings put global $it 0") }
                closeSystemErrorDialog(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))
                try {
                    base.evaluate()
                } finally {
                    restoreGlobal("hide_error_dialogs", previousHideErrorDialogs)
                    previousScales.forEach { (key, value) -> restoreGlobal(key, value) }
                    if (previousHome != null && !previousHome.startsWith("app.strata.launcher/")) {
                        shell("cmd package set-home-activity $previousHome")
                    }
                }
            }
        }
}

private val animationScales = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")

/** Puts back a global setting read with `settings get` ("null" = it was unset). */
private fun restoreGlobal(
    key: String,
    previous: String,
) {
    if (previous == "null" || previous.isEmpty()) {
        shell("settings delete global $key")
    } else {
        shell("settings put global $key $previous")
    }
}

private fun ComposeTestRule.count(tag: String): Int = onAllNodesWithTag(tag).fetchSemanticsNodes().size
