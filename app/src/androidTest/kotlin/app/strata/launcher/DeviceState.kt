package app.strata.launcher

import android.app.Activity
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.feature.onboarding.OnboardingTestTags
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

/** Shared device-state helpers for the HomeActivity device tests (search, home). */
private const val TAG = "DeviceState"

/**
 * Runs [command] as the shell user. This is a plain exec, not a shell: no pipes, quoting or redirection,
 * so filter the output in Kotlin.
 */
fun shell(command: String): String =
    ParcelFileDescriptor
        .AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
        .use { it.readBytes().decodeToString().trim() }

/** Who holds input focus, from `dumpsys window` (focused window and app) and the resumed activities. */
fun focusReport(): String {
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
 * keyguard up, which stops our resumed activity (CI run 41653871126, API 36, tHome07: no compose root left), and
 * the restarted, loaded system can then not show the IME (docs/impl-notes/M2.md item 15, CI run 41664919322).
 * Other apps (e.g. the system launcher) are closed. A crash dialog has no "Wait", only "Close".
 */
fun closeSystemErrorDialog(device: UiDevice): Boolean {
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
 * Waits until the activity's window has input focus (key events and the IME go to the focused window), closing
 * system error dialogs that hold it. When focus never arrives, fails with who holds it instead of timing out blind.
 */
fun <A : ComponentActivity> AndroidComposeTestRule<ActivityScenarioRule<A>, A>.awaitWindowFocus(
    device: UiDevice,
    timeoutMillis: Long = 30_000,
) {
    fun focused(): Boolean {
        var f = false
        activityRule.scenario.onActivity { f = it.window.decorView.hasWindowFocus() }
        return f
    }
    try {
        waitUntil(timeoutMillis) { focused().also { if (!it) closeSystemErrorDialog(device) } }
    } catch (e: ComposeTimeoutException) {
        val focus = focusReport()
        Log.e(TAG, "Window never focused. $focus")
        throw AssertionError("Window never focused. $focus", e)
    }
}

/**
 * Taps "All apps" and waits until the list layer (with its search field) is composed.
 *
 * "All apps" is on the rest page before the app list has loaded. The list arrives from the catalog snapshot
 * (Room on the IO dispatcher, assembled on Default), and Compose idling does not track that work. Until it
 * arrives, the LIST layer shows only the hidden rest page, with no list and no search field. So
 * `performClick()` followed by a lookup of a list node fails whenever the catalog is slower than the first
 * frames. CI run 41769795194 (API 34, tSearch10) clicked 1.9 s after the first frame and found no
 * `app_list_search_field`. Waiting for the list itself makes the step deterministic.
 */
fun ComposeTestRule.openAllApps(timeoutMillis: Long = 10_000) {
    fun count(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().size
    onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
    try {
        waitUntil(timeoutMillis) { count(AppListTestTags.LIST) > 0 && count(AppListTestTags.SEARCH_FIELD) > 0 }
    } catch (e: ComposeTimeoutException) {
        // The rest page's semantics are cleared once the LIST layer is set: "All apps" still present means
        // the tap did not register; absent means the layer switched but the app list never arrived.
        val allAppsStillShown = count(HomeTestTags.ALL_APPS) > 0
        throw AssertionError("List not shown within $timeoutMillis ms (allAppsStillShown=$allAppsStillShown)", e)
    }
}

/**
 * Completes the first-run flow (FR-ONBOARD-01) when it is showing, without changes: Welcome → Skip favourites → the
 * set-default step (Continue while Strata is home, else "Not now") → Done. The Orchestrator clears app data before
 * every test, so each HomeActivity test starts with it. Returns the number of screens passed.
 */
fun ComposeTestRule.passOnboarding(timeoutMillis: Long = 10_000): Int {
    fun count(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().size

    fun next(): String? =
        when {
            count(OnboardingTestTags.ROOT) == 0 -> null
            count(OnboardingTestTags.WELCOME) > 0 -> OnboardingTestTags.CONTINUE
            count(OnboardingTestTags.SKIP) > 0 -> OnboardingTestTags.SKIP
            count(OnboardingTestTags.NOT_NOW) > 0 -> OnboardingTestTags.NOT_NOW
            count(OnboardingTestTags.CONTINUE) > 0 -> OnboardingTestTags.CONTINUE
            else -> ""
        }
    var screens = 0
    while (screens < MAX_ONBOARDING_SCREENS) {
        waitForIdle()
        // The set-default step shows its buttons once the role check (off main) has finished.
        waitUntil(timeoutMillis) { next() != "" }
        val tag = next() ?: return screens
        onNodeWithTag(tag).performClick()
        screens++
    }
    throw AssertionError("Onboarding still shown after $screens screens")
}

private const val MAX_ONBOARDING_SCREENS = 6

/**
 * Device state for the home device tests, set before the activity starts and restored afterwards:
 * - Strata is the default home (best effort: `cmd package set-home-activity` is absent on some API levels), so
 *   the system launcher does not compete as HOME while ours runs.
 * - System error dialogs are suppressed (`hide_error_dialogs`, as on ATD images): an unresponsive background
 *   app is then killed instead of a dialog taking input focus. Any dialog already showing is closed.
 * - Window, transition and animator scales are 0 (the usual instrumented-test setting). On the software-rendered
 *   CI emulators an IME insets animation can run for seconds or hit the 10 s timeout (CI runs 41571533617 and
 *   41667894220), which makes Back timing depend on emulator load. The close logic keys off the IME target state
 *   either way (docs/impl-notes/M2.md items 16, 17); this only removes the load-dependent animation time.
 */
class DeviceStateRule : TestRule {
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
                // Each test is a short process that never lives the 10 s that mark a start good (ARCHITECTURE §10).
                // Without this, the third test in a row would boot in safe mode with its banner.
                File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "boot.state").delete()
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

/** A plain, opaque activity without Compose content (test APK manifest); see [coverForeignActivities]. */
class CoverActivity : Activity()

/** Lifecycle stages in which an activity can still be resumed or drawn. */
private val liveStages =
    listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.RESTARTED)

/**
 * Before the compose rule tears down: puts [CoverActivity] in front of every live [type] other than [owned] (the rule's
 * activity) and waits until each of them is STOPPED.
 *
 * Why: on API 29+ a Home press does not reach the rule's HomeActivity (a LAUNCHER-started, standard-type task) but
 * creates a second instance in the system's home task, which the rule's `ActivityScenario` does not own. At
 * teardown the rule closes only its own activity, so the system brings the other instance back to RESUMED. The
 * Compose test environment then drains its composition dispatcher on the instrumentation thread
 * (`withTestCoroutines` → `frameClockCoroutineScope.runTest {}`): a frame still queued there runs
 * `measureAndLayoutForTest()` on every RESUMED compose root, off the main thread, while the main thread lays out the
 * same root (insets/IME animation after the resume) → "performMeasureAndLayout called during measure layout" (CI
 * runs 41765134195 and 41820548452, API 34, tHome09). A root that is not RESUMED is not in the registry, so with
 * the cover in front (and revealed again after the rule's own activity closes) no root is left that the drain
 * could lay out concurrently.
 */
fun ComposeTestRule.coverForeignActivities(
    type: Class<out Activity>,
    owned: Activity?,
    timeoutMillis: Long = 15_000,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()

    fun foreign(): List<Activity> {
        val found = mutableListOf<Activity>()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            liveStages.flatMapTo(found) { stage -> monitor.getActivitiesInStage(stage).filter(type::isInstance) }
        }
        return found.filter { it !== owned }
    }
    if (foreign().isEmpty()) return
    val context = instrumentation.context
    context.startActivity(
        Intent().setClassName(context, CoverActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    try {
        waitUntil(timeoutMillis) { foreign().isEmpty() }
    } catch (e: ComposeTimeoutException) {
        throw AssertionError("Foreign ${type.simpleName} still live behind the cover. ${focusReport()}", e)
    }
}
