package app.strata.launcher.benchmark

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File

/**
 * The target app and its journeys, shared by every benchmark. The target is the `benchmark` build of `:app`
 * (`app.strata.launcher.benchmark`: release-like, R8, profileable, its own data), never the daily build.
 *
 * Journeys fail loudly when a screen does not appear: on an emulator (CI) the benchmarks are correctness checks
 * (dry run, no budgets), so a silent early return would hide a broken journey.
 */
internal object Strata {
    const val TARGET = "app.strata.launcher.benchmark"
    const val HOME_ACTIVITY = "app.strata.launcher.home.HomeActivity"

    // Test tags exposed as resource ids (testTagsAsResourceId) by the screens.
    const val HOME_ROOT = "home_root"
    const val ALL_APPS = "home_all_apps"
    const val PILL = "home_search_pill"
    const val LIST = "app_list"
    const val INDEX = "alphabet_index"
    const val SEARCH_FIELD_IN_LIST = "app_list_search_field"
    const val SEARCH_FIELD = "search_field"
    const val SEARCH_LAYER = "search_layer"
    const val CONTACTS_HEADER = "search_contacts_header"

    const val TIMEOUT_MS = 10_000L

    /**
     * The Home intent (T-PERF-01 "via Home intent"): MAIN + HOME aimed at HomeActivity, the intent the system sends
     * when Home is pressed. The benchmark app need not be the default home for this.
     */
    fun homeIntent(): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setClassName(TARGET, HOME_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** `am start` form of [homeIntent], for tests that do not run inside a MacrobenchmarkScope. */
    const val HOME_START_COMMAND =
        "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n $TARGET/$HOME_ACTIVITY"
}

/** Launches home with the Home intent and waits for its first frame. */
internal fun MacrobenchmarkScope.startHome() = startActivityAndWait(Strata.homeIntent())

/** Launches home, completes first-run onboarding when it shows, and waits until "All apps" is on screen. */
internal fun MacrobenchmarkScope.homeReady() {
    // Another home comes to front first, so the Home intent below is a real launch, not a re-delivery to a resumed
    // instance (with Strata as default home this lands on it, which is fine too).
    pressHome()
    startHome()
    device.passOnboardingAndWaitForHome()
}

/** [homeReady], then opens the A–Z list. */
internal fun MacrobenchmarkScope.openList(): UiObject2 {
    homeReady()
    device.clickOrFail(Strata.ALL_APPS)
    return device.waitOrFail(Strata.LIST)
}

/** [homeReady], then opens search from the home pill and returns the field. */
internal fun MacrobenchmarkScope.openSearch(): UiObject2 {
    homeReady()
    device.clickOrFail(Strata.PILL)
    return device.waitOrFail(Strata.SEARCH_FIELD)
}

/**
 * Waits for [res] on screen, answering a system error dialog of ANOTHER process meanwhile. On a loaded emulator System
 * UI can raise an ANR dialog that covers home (Macrobenchmark, run 44068284763: "Application Not Responding:
 * com.android.systemui", then 'home_all_apps' not found). System UI's is answered "Wait" (closing it restarts System UI
 * with the keyguard up), other apps' "Close". A dialog about the target itself is left alone, so its ANR still fails.
 */
internal fun UiDevice.findOrFail(res: String): UiObject2 {
    val end = SystemClock.uptimeMillis() + Strata.TIMEOUT_MS
    while (true) {
        wait(Until.findObject(By.res(res)), FIND_SLICE_MS)?.let { return it }
        answerForeignErrorDialog()
        if (SystemClock.uptimeMillis() >= end) {
            error(
                "'$res' not on screen after ${Strata.TIMEOUT_MS} ms (is the benchmark build of :app installed?) " +
                    focusedWindow() + " | " + whatUiAutomatorSees(res),
            )
        }
    }
}

/**
 * Device-ready gate (the app tests' `awaitSystemSettled`, docs/ci/RELIABILITY.md): waits, bounded and never failing,
 * until the system's broadcast queues are idle. The module's first test after boot failed twice (runs 44234299843,
 * 44234404782, tPerf09 idle home) and earlier as tPerf09_noJobsOrAlarms when that sorted first. Returns at once on a
 * settled system.
 */
internal fun UiDevice.awaitSystemSettled() {
    val done = java.util.concurrent.CountDownLatch(1)
    Thread({
        runCatching { executeShellCommand("cmd activity wait-for-broadcast-idle") }
        done.countDown()
    }, "settle-gate").apply {
        isDaemon = true
        start()
    }
    if (!done.await(SETTLE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        Log.w("Strata", "Broadcast queues still busy after $SETTLE_TIMEOUT_MS ms; continuing")
    }
}

// Six find timeouts: post-boot broadcast queues take longer than one screen change.
private const val SETTLE_TIMEOUT_MS = 6 * Strata.TIMEOUT_MS

/**
 * What UiAutomator can see when [res] is missing: the packages of the windows it reads and the resource ids in them,
 * plus a screenshot in the additional test output (collected by GMD). Run 44234299843 failed with home focused and
 * fully drawn, yet neither onboarding nor home found, and nothing showed why.
 */
private fun UiDevice.whatUiAutomatorSees(res: String): String {
    val xml =
        runCatching {
            java.io
                .ByteArrayOutputStream()
                .also { dumpWindowHierarchy(it) }
                .toString(Charsets.UTF_8.name())
        }.getOrElse { return "hierarchy dump failed: $it" }
    val packages = Regex("""package="([^"]*)"""").findAll(xml).map { it.groupValues[1] }.toSortedSet()
    val ids =
        Regex("""resource-id="([^"]+)"""")
            .findAll(xml)
            .map { it.groupValues[1] }
            .distinct()
            .take(MAX_IDS)
            .toList()
    val shot =
        runCatching {
            val dir =
                InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
                    ?: InstrumentationRegistry
                        .getInstrumentation()
                        .context.externalMediaDirs
                        .first()
            File(dir, "missing-$res-${System.currentTimeMillis()}.png").takeIf { takeScreenshot(it) }?.name ?: "none"
        }.getOrElse { "none ($it)" }
    return "windows: $packages; resource ids (${ids.size}): $ids; screenshot: $shot"
}

private const val MAX_IDS = 40

private fun UiDevice.focusedWindow(): String =
    executeShellCommand("dumpsys window")
        .lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("mCurrentFocus") }
        .orEmpty()

/** Answers a showing "isn't responding" / "keeps stopping" dialog unless it is about [Strata.TARGET]. */
private fun UiDevice.answerForeignErrorDialog() {
    val wait = findObject(By.res("android", "aerr_wait"))
    val close = findObject(By.res("android", "aerr_close"))
    if (wait == null && close == null) return
    val focus = focusedWindow()
    if (Strata.TARGET in focus) return
    val button = if ("com.android.systemui" in focus) wait ?: close else close ?: wait
    Log.w("Strata", "Answering a system error dialog of another process: $focus")
    button?.click()
}

private const val FIND_SLICE_MS = 1_000L

internal fun UiDevice.waitOrFail(res: String): UiObject2 = findOrFail(res)

/**
 * Finds [res] and runs [action] on it, finding it again when the node goes stale between the lookup and the action.
 * A scroll, fling or index scrub recomposes the list, so a node found before can be gone by the click (Baseline
 * Profile, run 43994307244: StaleObjectException clicking the list's search field right after the index scrub).
 * Not a retry of the journey: the same step on the same screen, bounded by [Strata.TIMEOUT_MS]; a missing node still
 * fails through [findOrFail].
 */
internal fun <T> UiDevice.onFresh(
    res: String,
    action: (UiObject2) -> T,
): T {
    val end = SystemClock.uptimeMillis() + Strata.TIMEOUT_MS
    while (true) {
        try {
            return action(findOrFail(res))
        } catch (e: StaleObjectException) {
            if (SystemClock.uptimeMillis() >= end) throw e
        }
    }
}

/**
 * Completes the first-run flow if it is showing (fresh benchmark install): Welcome -> Continue, Skip favourites,
 * Not now / Continue on the set-default step, Done. Then waits for home's "All apps".
 */
internal fun UiDevice.passOnboardingAndWaitForHome() {
    awaitSystemSettled()
    val order = listOf(ONBOARDING_ORDER_WELCOME, ONBOARDING_ORDER_REST).flatten()
    var screens = 0
    while (screens < MAX_ONBOARDING_SCREENS) {
        waitForIdle()
        // Onboarding or home, whichever shows: not "no onboarding within 1.5 s". On a slow emulator onboarding came up
        // later and covered home, and the journey then waited for a home that was not showing (run 44129238421).
        val first =
            pollFor(Strata.TIMEOUT_MS) {
                when {
                    hasObject(By.res(ONBOARDING_ROOT_TAG)) -> ONBOARDING_ROOT_TAG
                    hasObject(By.res(Strata.ALL_APPS)) -> Strata.ALL_APPS
                    else -> null
                }
            }
        if (first != ONBOARDING_ROOT_TAG) break
        // The set-default step shows its buttons once the role check (off main) has finished. After the last click
        // ("Not now" / "Go to home") the layer is still leaving when the root is checked above, so the poll also ends
        // when the root is gone (CI run 43563634680, BackgroundCostTest: it waited 10 s for buttons of a closed layer).
        val next =
            pollFor(Strata.TIMEOUT_MS) {
                if (!hasObject(By.res(ONBOARDING_ROOT_TAG))) {
                    ONBOARDING_GONE
                } else {
                    order.firstOrNull { (marker, _) -> hasObject(By.res(marker)) }?.second
                }
            } ?: error("onboarding is showing but none of its buttons appeared")
        if (next == ONBOARDING_GONE) break
        // Re-find right before the click: the object seen by the poll may belong to a frame that has since changed.
        clickOrFail(next)
        screens++
    }
    findOrFail(Strata.ALL_APPS)
}

/**
 * Waits until every activity record of the target is STOPPED (from `dumpsys activity activities`, the `state=` /
 * `mState=` of each `Hist` entry). `am start -W` of another app returns once that app is displayed, but the activity
 * behind it is stopped only after the new one reports idle. A "warm return" measured before that brings a still-visible
 * window to front, which draws no new frame, and StartupTimingMetric fails with "No RT frame slice associated with
 * UI thread frame slice" (runs 43625970551 and 44068284763, T-PERF-02). Fails, never passes, when no state is found.
 */
internal fun UiDevice.awaitTargetStopped(timeoutMs: Long = 2 * Strata.TIMEOUT_MS) {
    fun states(): List<String> =
        executeShellCommand("dumpsys activity activities")
            .split(Regex("""(?m)^\s*\*?\s*Hist\s*#"""))
            .drop(1)
            .filter { entry -> entry.lineSequence().first().contains("${Strata.TARGET}/") }
            .mapNotNull { entry -> STATE.find(entry)?.groupValues?.get(1) }
    // The platform stops the covered activity at the latest 10 s after the new one started, if it never reports idle.
    val end = SystemClock.uptimeMillis() + timeoutMs
    var last = states()
    while (last.isEmpty() || last.any { it != "STOPPED" }) {
        check(SystemClock.uptimeMillis() < end) {
            "${Strata.TARGET} not stopped behind the other app within $timeoutMs ms (states: $last)"
        }
        SystemClock.sleep(POLL_MS)
        last = states()
    }
}

private val STATE = Regex("""\bm?[sS]tate=([A-Z_]+)""")

/** Clicks [res] once it is on screen, finding it again if it went stale before the click ([onFresh]). */
internal fun UiDevice.clickOrFail(res: String) = onFresh(res) { it.click() }

/** Scrolls the list to its end (T-PERF-07 "after a full list scroll"). */
internal fun UiDevice.scrollListToEnd() {
    val list = findOrFail(Strata.LIST)
    list.setGestureMargin(displayWidth / 5)
    var guard = 0
    while (list.scroll(Direction.DOWN, 1.0f) && guard++ < MAX_SCROLLS) Unit
}

private fun <T> pollFor(
    timeoutMs: Long,
    probe: () -> T?,
): T? {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        probe()?.let { return it }
        Thread.sleep(POLL_MS)
    }
    return probe()
}

private const val ONBOARDING_ROOT_TAG = "onboarding_root"
private val ONBOARDING_ORDER_WELCOME = listOf("onboarding_welcome" to "onboarding_continue")
private val ONBOARDING_ORDER_REST =
    listOf(
        "onboarding_skip" to "onboarding_skip",
        "onboarding_not_now" to "onboarding_not_now",
        // The Done step (its column is tagged onboarding_done) has a single "Go to home" button tagged continue.
        "onboarding_done" to "onboarding_continue",
        "onboarding_continue" to "onboarding_continue",
    )
private const val ONBOARDING_GONE = "<onboarding gone>"
private const val MAX_ONBOARDING_SCREENS = 6
private const val MAX_SCROLLS = 200
private const val POLL_MS = 100L

/** From home: the pill menu -> Themes -> the theme library. */
internal fun UiDevice.openThemeLibraryFromHome() {
    clickOrFail("home_search_pill_menu")
    val themes =
        wait(Until.findObject(By.res("home_menu_themes")), Strata.TIMEOUT_MS) ?: findObject(By.text("Themes"))
    checkNotNull(themes) { "Themes menu item not found" }.click()
    findOrFail("theme_library")
}

/** Applies the theme [id] through the library UI, starting from a Home intent (no MacrobenchmarkScope needed). */
internal fun UiDevice.applyTheme(id: String) {
    executeShellCommand(Strata.HOME_START_COMMAND)
    passOnboardingAndWaitForHome()
    openThemeLibraryFromHome()
    clickOrFail("theme_row_$id")
    waitForIdle()
}
