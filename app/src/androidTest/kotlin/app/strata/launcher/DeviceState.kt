package app.strata.launcher

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
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

/** Longest wait for one state change in a device test (a role grant, a layer, a stored value); waits end on the state. */
const val DEVICE_TIMEOUT_MS = 15_000L

/** The focused window is a crash dialog ("Application Error: <package>"). */
private val CRASH_DIALOG_FOCUS = Regex("mCurrentFocus=Window\\{[^}]*Application Error: ")

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
    if (close == null && wait == null) {
        // API 26: the crash dialog of a background process (the test APK's ":crash" fixture) holds focus but its buttons
        // are not reachable by id (CI run 43208833512, tWidget05 teardown). A crash dialog is cancelable and Back
        // answers it like "Close". ANR dialogs keep the branch below (their "Wait" matters for System UI).
        val focused = focusReport()
        if (!CRASH_DIALOG_FOCUS.containsMatchIn(focused)) return false
        Log.w(TAG, "Closing a crash dialog with Back: $focused")
        device.pressBack()
        return true
    }
    val focus = focusReport()
    val systemUi = focus.contains("Not Responding: com.android.systemui")
    val button = if (systemUi) wait ?: close else close ?: wait
    Log.w(TAG, "Answering system error dialog with " + (if (button === wait) "Wait" else "Close") + ": " + focus)
    try {
        button?.click()
    } catch (e: StaleObjectException) {
        // The dialog went away between the lookup and the click (CI run 44129245277, API 34, HomeSmokeTest): nothing
        // is left to answer.
        Log.w(TAG, "System error dialog closed before it was answered", e)
    }
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
 * Device-ready gate: waits (bounded by [SETTLE_TIMEOUT_MS], never failing) until the system's broadcast queues are
 * idle. The first tests after an emulator boots ran while the boot broadcasts were still being delivered: run
 * 44183532175 (API 33, HomeSmokeTest, the first test of the run) saw HomeActivity displayed after 4 s, 2.5 s frames and
 * system_server lock contention, and a click on a focused window still failed to inject. Returns at once on a settled
 * system. Not a sleep: `wait-for-broadcast-idle` ends when the queues are empty.
 */
fun awaitSystemSettled() {
    val done = java.util.concurrent.CountDownLatch(1)
    val waiter =
        Thread({
            runCatching { shell("cmd activity wait-for-broadcast-idle") }
            done.countDown()
        }, "settle-gate").apply {
            isDaemon = true
            start()
        }
    if (!done.await(SETTLE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        Log.w(TAG, "Broadcast queues still busy after $SETTLE_TIMEOUT_MS ms; continuing (${waiter.name})")
    }
}

// Post-boot broadcast queues take longer than one state change: four device timeouts bound the gate.
private const val SETTLE_TIMEOUT_MS = 4 * DEVICE_TIMEOUT_MS

/**
 * Waits until a RESUMED activity of [type] has window focus, i.e. WindowManager shows it and input goes to it. Compose
 * finds nodes as soon as they are composed, which can be well before that: CI run 44068284763 (API 34, tSearch13)
 * clicked onboarding 1.3 s before HomeActivity was displayed, the touch landed on the window below (another app) and
 * injection was refused ("Failed to inject touch input"). Fails with who holds focus instead.
 */
fun ComposeTestRule.awaitFocusedActivity(
    type: Class<out Activity>,
    timeoutMillis: Long = DEVICE_TIMEOUT_MS,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()

    fun focused(): Boolean {
        var f = false
        instrumentation.runOnMainSync {
            f =
                ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .any { type.isInstance(it) && it.window.decorView.hasWindowFocus() }
        }
        return f
    }
    val device = UiDevice.getInstance(instrumentation)
    try {
        waitUntil(timeoutMillis) { focused().also { if (!it) closeSystemErrorDialog(device) } }
    } catch (e: ComposeTimeoutException) {
        throw AssertionError("No focused ${type.simpleName} within $timeoutMillis ms. ${focusReport()}", e)
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
    awaitSystemSettled()
    // Touches go to the focused window only: wait until home (or onboarding's own activity) is shown and focused.
    awaitFocusedActivity(ComponentActivity::class.java)
    var screens = 0
    while (screens < MAX_ONBOARDING_SCREENS) {
        waitForIdle()
        // The set-default step shows its buttons once the role check (off main) has finished.
        // The tag clicked is the one the wait saw. Not a second next(): between two reads the layer can be mid-change
        // (root shown, no button yet), and the second read returned "" and clicked onNodeWithTag("") (GMD API 36,
        // dispatch run 44289941495, tSearch13's @Before: "Failed to inject touch input ... (TestTag = '')").
        var seen: String? = ""
        waitUntil(timeoutMillis) { next().also { seen = it } != "" }
        val tag = seen ?: return screens
        onNodeWithTag(tag).performClick()
        screens++
    }
    throw AssertionError("Onboarding still shown after $screens screens")
}

private const val MAX_ONBOARDING_SCREENS = 6

/**
 * Device state for the home device tests, set before the activity starts and restored afterwards:
 * - Strata is the default home, so the system launcher does not compete as HOME while ours runs. Verified, not assumed:
 *   on a stalled emulator `set-home-activity` can fail ("Failed to set default home" after 15 s, nightly run
 *   43842913826, API 33), and the test then ran against the system launcher and left a stuck Recents transition
 *   behind for the next test. The rule retries until the platform reports Strata as home, else fails as a
 *   precondition (see docs/ci/RELIABILITY.md).
 * - System error dialogs are suppressed (`hide_error_dialogs`, as on ATD images): an unresponsive background
 *   app is then killed instead of a dialog taking input focus. Any dialog already showing is closed.
 * - Window, transition and animator scales are 0 (the usual instrumented-test setting). On the software-rendered
 *   CI emulators an IME insets animation can run for seconds or hit the 10 s timeout (CI runs 41571533617 and
 *   41667894220), which makes Back timing depend on emulator load. The close logic keys off the IME target state
 *   either way (docs/impl-notes/M2.md items 16, 17); this only removes the load-dependent animation time.
 */
class DeviceStateRule(
    /**
     * A navigation mode a test needs (`gestural` / `threebutton`), switched before its activity starts. It wins over
     * the job's `strata.navMode`: a test that needs gestures needs them in every job. Switching mid-test instead (after
     * the activity started) left API 29 without a working Back gesture although every observable state was right
     * (flake-hunt run 44129238421); switched before the start, the same test passed in every run.
     */
    private val navModeFor: (Description) -> String? = { null },
) : TestRule {
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
                makeStrataDefaultHome(component)
                val previousHideErrorDialogs = shell("settings get global hide_error_dialogs")
                shell("settings put global hide_error_dialogs 1")
                val previousScales = animationScales.associateWith { shell("settings get global $it") }
                animationScales.forEach { shell("settings put global $it 0") }
                var restoreNav: (() -> Unit)? = null
                try {
                    // Inside the try: a failing nav-mode switch must still restore everything changed above.
                    restoreNav = applyNavMode(navModeFor(description))
                    closeSystemErrorDialog(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))
                    // Each test is a short process that never lives the 10 s that mark a start good (ARCHITECTURE
                    // §10). Without this, the third test in a row would boot in safe mode with its banner.
                    File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "boot.state")
                        .delete()
                    base.evaluate()
                } finally {
                    restoreNav?.let { runCatching(it).onFailure { e -> Log.e(TAG, "nav mode not restored", e) } }
                    restoreGlobal("hide_error_dialogs", previousHideErrorDialogs)
                    previousScales.forEach { (key, value) -> restoreGlobal(key, value) }
                    if (previousHome != null && !previousHome.startsWith("app.strata.launcher/")) {
                        shell("cmd package set-home-activity $previousHome")
                    }
                }
            }
        }
}

/**
 * T-COMPAT-02: the navigation mode for this run, from the instrumentation argument `strata.navMode` (`gestural` or
 * `threebutton`; API 29+, where both are runtime overlays). Absent: the device's own mode is used and left alone.
 * Fails when the wanted overlay is not active afterwards. Returns how to put back exactly the navigation overlays that
 * were enabled before (two-button, gestural variants, or none), or null when nothing changed.
 */
private fun applyNavMode(testWants: String?): (() -> Unit)? {
    val wanted = testWants ?: InstrumentationRegistry.getArguments().getString("strata.navMode") ?: return null
    return switchNavMode(wanted)
}

/**
 * Switches the navigation mode to [wanted] (`gestural` or `threebutton`, API 29+) for one test; returns how to restore
 * the overlays enabled before, or null when nothing changed. Fails when the overlay is not active afterwards.
 */
fun switchNavMode(wanted: String): (() -> Unit)? {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return null
    val overlay =
        NAV_OVERLAYS[wanted] ?: throw IllegalArgumentException("strata.navMode=$wanted: use ${NAV_OVERLAYS.keys}")
    val before = enabledNavOverlays()
    val settingBefore = navModeSetting()
    if (before == listOf(overlay)) return null
    // Built before the switch, so a switch that fails half-way (overlay enabled, mode never reported) is undone too.
    val restore = {
        if (before.isEmpty()) {
            // No navigation overlay was enabled: the device default. Turn ours off again.
            Log.i(TAG, "nav mode restore: " + shell("cmd overlay disable $overlay"))
        } else {
            before.forEach { setNavOverlay(it) }
        }
        val restored = enabledNavOverlays()
        if (restored != before) Log.e(TAG, "nav mode restore: expected $before, found $restored")
        if (!pollUntil(DEVICE_TIMEOUT_MS) { navModeSetting() == settingBefore }) {
            Log.e(TAG, "nav mode restore: navigation_mode is ${navModeSetting()}, was $settingBefore")
        }
    }
    try {
        setNavOverlay(overlay)
        val after = enabledNavOverlays()
        check(
            overlay in after,
        ) { "strata.navMode=$wanted: $overlay not active after enable-exclusive (enabled: $after)" }
        awaitNavModeSetting(NAV_MODE_SETTING.getValue(wanted), "strata.navMode=$wanted")
    } catch (e: Throwable) {
        runCatching(restore).onFailure { e.addSuppressed(it) }
        throw e
    }
    return restore
}

private fun navModeSetting(): String = shell("settings get secure navigation_mode").trim()

/**
 * Waits until System UI reports [expected] as `navigation_mode`. Replaces a fixed 3 s sleep, which on a loaded API 29
 * emulator ended before System UI had re-laid out its bar and gesture handler (nightly runs 43699451179 and
 * 43842913826, searchClosesWithBackFromTheIndexEdge).
 */
private fun awaitNavModeSetting(
    expected: String,
    what: String,
) {
    check(pollUntil(DEVICE_TIMEOUT_MS) { navModeSetting() == expected }) {
        "$what: navigation_mode is ${navModeSetting()} after $DEVICE_TIMEOUT_MS ms, expected $expected"
    }
}

/** Enabled overlays of the `com.android.internal.systemui.navbar.*` family, from `cmd overlay list android`. */
private fun enabledNavOverlays(): List<String> =
    shell("cmd overlay list android")
        .lines()
        .map(String::trim)
        .filter { it.startsWith("[x]") }
        .map { it.removePrefix("[x]").trim() }
        .filter { it.startsWith(NAV_OVERLAY_PREFIX) }

private fun setNavOverlay(overlay: String) {
    // System UI re-lays out its navigation bar and gesture insets asynchronously; callers wait for navigation_mode.
    Log.i(TAG, "nav mode: " + shell("cmd overlay enable-exclusive --category $overlay"))
}

private const val NAV_OVERLAY_PREFIX = "com.android.internal.systemui.navbar."

private val NAV_OVERLAYS =
    mapOf(
        "gestural" to "com.android.internal.systemui.navbar.gestural",
        "threebutton" to "com.android.internal.systemui.navbar.threebutton",
    )

/** Whether the platform reports Strata (the target package) as the default home: the HOME role on API 29+, else `MAIN/HOME`. */
fun strataIsDefaultHome(): Boolean {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
    } else {
        context.packageManager
            .resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo
            ?.packageName == context.packageName
    }
}

/**
 * Makes [component] the default home and waits until the platform says so. Re-issues the command while the system is
 * stalled (a failed `set-home-activity` answers "Error: …"); fails as a precondition after [SET_HOME_TIMEOUT_MS].
 */
private fun makeStrataDefaultHome(component: String) {
    val deadline = SystemClock.uptimeMillis() + SET_HOME_TIMEOUT_MS
    var answer: String
    do {
        answer = shell("cmd package set-home-activity $component")
        Log.i(TAG, "set-home-activity: $answer")
        if (pollUntil(SET_HOME_RETRY_MS) { strataIsDefaultHome() }) return
    } while (SystemClock.uptimeMillis() < deadline)
    throw AssertionError(
        "Precondition: Strata is not the default home after $SET_HOME_TIMEOUT_MS ms (last answer: '$answer'). " +
            "The emulator is stalled or the role is blocked; see docs/ci/RELIABILITY.md. ${focusReport()}",
    )
}

/** Polls [condition] off the main thread every [POLL_MS] until it holds or [timeoutMillis] pass; returns the last result. */
fun pollUntil(
    timeoutMillis: Long,
    condition: () -> Boolean,
): Boolean {
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    while (SystemClock.uptimeMillis() < deadline) {
        if (condition()) return true
        SystemClock.sleep(POLL_MS)
    }
    return condition()
}

private const val SET_HOME_TIMEOUT_MS = 2 * DEVICE_TIMEOUT_MS

// Re-issue the command three times per device timeout: a stalled system answered after ~15 s (one DEVICE_TIMEOUT_MS).
private const val SET_HOME_RETRY_MS = DEVICE_TIMEOUT_MS / 3
private const val POLL_MS = 100L

/**
 * Opens Recents and comes back to the rule's own HomeActivity [owned]. Recents is waited for before anything else is
 * pressed: a Back sent before the Recents transition has started races it (nightly run 43599797441, API 31, tHome07:
 * Back 40 ms after the Recents key, then Recents opened over the paused activity and its transition never ended, so the
 * rule's `close()` waited 45 s for DESTROYED and the next test's window never got focus). The return is an explicit
 * start of the rule's task ([bringHomeToFront]), which also ends the Recents transition.
 */
fun ComposeTestRule.visitRecentsAndReturn(
    owned: app.strata.launcher.home.HomeActivity,
    device: UiDevice,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()

    fun ownedFocused(): Boolean {
        var focused = false
        instrumentation.runOnMainSync { focused = owned.window.decorView.hasWindowFocus() }
        return focused
    }
    device.pressRecentApps()
    try {
        waitUntil(DEVICE_TIMEOUT_MS) { !ownedFocused() }
    } catch (e: ComposeTimeoutException) {
        throw AssertionError("Recents did not take focus from home. ${focusReport()}", e)
    }
    bringHomeToFront(owned, device)
}

/**
 * The navigation mode that `settings get secure navigation_mode` reports once System UI has applied an overlay
 * (System UI writes it after re-reading the overlaid config): 0 three-button, 2 gestural.
 */
private val NAV_MODE_SETTING = mapOf("gestural" to "2", "threebutton" to "0")

/**
 * Whether [activity]'s window insets match gesture navigation ([gestural]) or not: with gestures the left and right
 * system-gesture insets (the Back edges) are non-zero, with three buttons they are zero. This is what the app sees, so
 * it also covers the configuration change the overlay causes (the activity is recreated on API 29).
 */
fun windowMatchesNavMode(
    activity: Activity,
    gestural: Boolean,
): Boolean {
    var matches = false
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
        if (insets != null) {
            val edges = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
            matches = (edges.left > 0 && edges.right > 0) == gestural
        }
    }
    return matches
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

/** The resumed HomeActivity, if any (the compose test only sees resumed activities). */
fun resumedHomeActivity(): app.strata.launcher.home.HomeActivity? {
    var home: app.strata.launcher.home.HomeActivity? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        home =
            ActivityLifecycleMonitorRegistry
                .getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<app.strata.launcher.home.HomeActivity>()
                .firstOrNull()
    }
    return home
}

/**
 * Returns to the rule's own HomeActivity [owned] after another app came to front: starts HomeActivity explicitly
 * (`singleTask`, so the rule's task comes back; a non-Home intent, which `onNewIntent` ignores) and waits until [owned] is
 * resumed and focused. Never a Back loop (docs/impl-notes/M6.md).
 *
 * The start is issued again every [RESTART_EVERY_MS] while [owned] is not in front: the app that was opened can put
 * another activity on top after home already came back. CI run 42961426106 (API 36, tHome05): the calendar's first run
 * started the Google sign-in flow 0.4 s after HomeActivity had resumed, and a single start left it covered.
 */
fun ComposeTestRule.bringHomeToFront(
    owned: app.strata.launcher.home.HomeActivity,
    device: UiDevice,
    timeoutMillis: Long = 30_000,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()

    fun start() =
        instrumentation.context.startActivity(
            Intent(instrumentation.targetContext, app.strata.launcher.home.HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    start()
    var lastStart = android.os.SystemClock.uptimeMillis()
    try {
        waitUntil(timeoutMillis) {
            var focused = false
            instrumentation.runOnMainSync { focused = owned.window.decorView.hasWindowFocus() }
            val front = focused && resumedHomeActivity() === owned
            if (!front) {
                closeSystemErrorDialog(device)
                if (android.os.SystemClock.uptimeMillis() - lastStart >= RESTART_EVERY_MS) {
                    start()
                    lastStart = android.os.SystemClock.uptimeMillis()
                }
            }
            front
        }
    } catch (e: ComposeTimeoutException) {
        throw AssertionError("The rule's HomeActivity did not return to front. ${focusReport()}", e)
    }
}

private const val RESTART_EVERY_MS = 2_000L
