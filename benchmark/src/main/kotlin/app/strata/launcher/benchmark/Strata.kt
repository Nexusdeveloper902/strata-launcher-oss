package app.strata.launcher.benchmark

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

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
    device.findOrFail(Strata.ALL_APPS).click()
    return device.waitOrFail(Strata.LIST)
}

/** [homeReady], then opens search from the home pill and returns the field. */
internal fun MacrobenchmarkScope.openSearch(): UiObject2 {
    homeReady()
    device.findOrFail(Strata.PILL).click()
    return device.waitOrFail(Strata.SEARCH_FIELD)
}

internal fun UiDevice.findOrFail(res: String): UiObject2 =
    wait(Until.findObject(By.res(res)), Strata.TIMEOUT_MS)
        ?: error("'$res' not on screen after ${Strata.TIMEOUT_MS} ms (is the benchmark build of :app installed?)")

internal fun UiDevice.waitOrFail(res: String): UiObject2 = findOrFail(res)

/**
 * Completes the first-run flow if it is showing (fresh benchmark install): Welcome -> Continue, Skip favourites,
 * Not now / Continue on the set-default step, Done. Then waits for home's "All apps".
 */
internal fun UiDevice.passOnboardingAndWaitForHome() {
    val order = listOf(ONBOARDING_ORDER_WELCOME, ONBOARDING_ORDER_REST).flatten()
    var screens = 0
    while (screens < MAX_ONBOARDING_SCREENS) {
        waitForIdle()
        if (wait(Until.hasObject(By.res(ONBOARDING_ROOT_TAG)), SHORT_WAIT_MS) != true) break
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
        findOrFail(next).click()
        screens++
    }
    findOrFail(Strata.ALL_APPS)
}

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
private const val SHORT_WAIT_MS = 1_500L
private const val POLL_MS = 100L

/** From home: the pill menu -> Themes -> the theme library. */
internal fun UiDevice.openThemeLibraryFromHome() {
    findOrFail("home_search_pill_menu").click()
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
    findOrFail("theme_row_$id").click()
    waitForIdle()
}
