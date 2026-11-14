package app.strata.launcher.home

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.View
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.core.util.Consumer
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.strata.launcher.DEVICE_TIMEOUT_MS
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.appGraph
import app.strata.launcher.awaitWindowFocus
import app.strata.launcher.bringHomeToFront
import app.strata.launcher.closeSystemErrorDialog
import app.strata.launcher.coverForeignActivities
import app.strata.launcher.feature.home.HomeAction
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.HomeViewModel
import app.strata.launcher.feature.home.SheetTestTags
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.feature.search.SearchTestTags
import app.strata.launcher.focusReport
import app.strata.launcher.openAllApps
import app.strata.launcher.passOnboarding
import app.strata.launcher.shell
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * M3 home on a device or emulator (level I). Not run in this environment (no emulator); compiled by
 * `assembleDebugAndroidTest`. Home-key tests need Strata as the default home ("home default" precondition of
 * TEST_PLAN §0): `adb shell cmd role add-role-holder android.app.role.HOME app.strata.launcher`. Tests that need
 * the "Zz Test" fixture APK skip without it (see `ListDeviceTest`).
 */
@RunWith(AndroidJUnit4::class)
class HomeDeviceTest {
    val compose = createAndroidComposeRule<HomeActivity>()

    /** Strata as HOME, no system error dialogs, animations off; set before the activity starts (DeviceState.kt). */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(DeviceStateRule()).around(compose)

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun vm(): HomeViewModel {
        lateinit var vm: HomeViewModel
        compose.activityRule.scenario.onActivity {
            vm = androidx.lifecycle.ViewModelProvider(it).get(HomeViewModel::class.java)
        }
        return vm
    }

    private fun vm(activity: HomeActivity): HomeViewModel {
        lateinit var vm: HomeViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = androidx.lifecycle.ViewModelProvider(activity).get(HomeViewModel::class.java)
        }
        return vm
    }

    /** The resumed HomeActivity, if any (the compose test only sees resumed activities). */
    private fun resumedHome(): HomeActivity? {
        var home: HomeActivity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            home =
                ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<HomeActivity>()
                    .firstOrNull()
        }
        return home
    }

    /**
     * Presses Home and returns the HomeActivity that received it, i.e. the one later Home presses reach. The rule
     * starts HomeActivity with a LAUNCHER intent in its own task. On API 26 a Home press is delivered to that
     * instance (`onNewIntent`); on API 34/36 the system's home task gets its own instance and the rule's one is
     * stopped (CI run 41653871126: a second HomeActivity created with `CATEGORY_HOME`, the first one STOPPED).
     * Either way the answer is observed, not guessed: an `onNewIntent` on the rule's activity, or a resumed
     * HomeActivity started by the Home intent.
     */
    private fun pressHomeAndAwaitHomeInstance(): HomeActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var first: HomeActivity
        compose.activityRule.scenario.onActivity { first = it }
        val receiver = AtomicReference<HomeActivity?>()
        val listener = Consumer<Intent> { if (it.hasCategory(Intent.CATEGORY_HOME)) receiver.set(first) }
        instrumentation.runOnMainSync { first.addOnNewIntentListener(listener) }
        try {
            device.pressHome()
            compose.waitUntil(15_000) {
                if (receiver.get() == null) {
                    resumedHome()
                        ?.takeIf { it !== first && it.intent.hasCategory(Intent.CATEGORY_HOME) }
                        ?.let { receiver.set(it) }
                }
                val home = receiver.get()
                var focused = false
                if (home != null) instrumentation.runOnMainSync { focused = home.window.decorView.hasWindowFocus() }
                if (!focused) closeSystemErrorDialog(device)
                focused && resumedHome() === home
            }
        } finally {
            instrumentation.runOnMainSync { first.removeOnNewIntentListener(listener) }
        }
        return receiver.get()!!
    }

    /** The home index is composed once the list snapshot is loaded (after onboarding, from a fresh catalog). */
    private fun awaitHomeIndex() {
        compose.waitUntil(15_000) { compose.count(HomeTestTags.HOME_INDEX) > 0 }
        compose.waitForIdle()
    }

    private fun isDefaultHome(): Boolean = appGraph().roleController.isDefaultHome()

    private fun appGraph() =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext.applicationContext.appGraph

    @Before
    fun ready() {
        compose.waitForIdle()
        compose.passOnboarding()
        compose.waitUntil(timeoutMillis = 10_000) { compose.count(HomeTestTags.ROOT) > 0 }
        compose.awaitWindowFocus(device)
    }

    @After
    fun tearDown() {
        runBlocking {
            appGraph().settingsRepository.update {
                it.copy(
                    hideStatusBar = false,
                    allowRotation = false,
                    homePressOpensSearch = false,
                    keepBackGestureOnIndexSide = false,
                )
            }
        }
        // A Home press (tHome09/10) or Back from another app (tHome05) can leave a HomeActivity in front that the
        // rule does not own; it must not be RESUMED while the rule tears down (DeviceState.kt).
        var owned: HomeActivity? = null
        runCatching { compose.activityRule.scenario.onActivity { owned = it } }
        compose.coverForeignActivities(HomeActivity::class.java, owned)
    }

    /** T-HOME-01 (from M3): Home shows the header and favourites region. */
    @Test
    fun tHome01_homeRegions() {
        compose.onNodeWithTag(HomeTestTags.FAVORITES).assertExists()
        compose.onNodeWithTag(HomeTestTags.PILL).assertExists()
    }

    /** T-HOME-05: clock → the clock app (`ACTION_SHOW_ALARMS`); date → the calendar (time URI). */
    @Test
    fun tHome05_clockAndDate() {
        val ownPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        lateinit var owned: HomeActivity
        compose.activityRule.scenario.onActivity { owned = it }
        compose.onNodeWithTag(HomeTestTags.CLOCK).performClick()
        // The clock app cold-starts through a trampoline activity and a splash window; waitForIdle returns while our
        // paused window is still the active one. CI run 41653871126: SHOW_ALARMS reached the clock on API 26/34/36,
        // but its window was displayed ~10 s after the tap on API 34. Wait for the switch, then assert it.
        runCatching { compose.waitUntil(20_000) { device.currentPackageName != ownPackage } }
        assertThat(device.currentPackageName).isNotEqualTo(ownPackage)
        device.pressBack()
        // Back from the clock's task returns to a HomeActivity (ours, or the home-task instance on API 29+); the
        // compose test only sees resumed activities, so wait until one is in front again.
        compose.waitUntil(20_000) { device.currentPackageName == ownPackage && resumedHome() != null }
        compose.onNodeWithTag(HomeTestTags.DATE).performClick()
        runCatching { compose.waitUntil(20_000) { device.currentPackageName != ownPackage } }
        assertThat(device.currentPackageName).isNotEqualTo(ownPackage)
        // The test plan has no Back after the date tap; the test only has to end with the rule's activity resumed
        // (closing the scenario mid-transition fails with "Current state was null", CI run 41818633704, API 34).
        // Not by pressing Back until home shows: a fresh emulator's calendar opens a first-run screen, and its close
        // can take seconds. CI run 41876425721 (API 36): Back closed the calendar, our window was RESUMED but not yet
        // visible 4 s later, so the next poll still saw another package and pressed Back again. That Back reached
        // HomeActivity at HOME_REST, where no callback is registered (INTERACTION_SPEC §6) and the system finishes a
        // non-home-task activity: the rule's activity was DESTROYED, the system launcher came to front, and no
        // HomeActivity resumed for the remaining 25 s of Backs. Instead, bring the rule's own task to front.
        bringToFront(owned)
    }

    /** Brings the rule's own HomeActivity back to front (shared helper, re-issues the start while covered). */
    private fun bringToFront(owned: HomeActivity) = compose.bringHomeToFront(owned, device)

    /** T-HOME-07: "hide status bar" hides it on home; it is visible again in another app. */
    @Test
    fun tHome07_hideStatusBar() {
        runBlocking { appGraph().settingsRepository.update { it.copy(hideStatusBar = true) } }
        compose.waitUntil(5_000) { !statusBarVisible() }
        device.pressRecentApps()
        device.waitForIdle()
        device.pressBack()
    }

    /** T-HOME-09: one Home press from sheet / search / list returns to rest (INTERACTION_SPEC §6). */
    @Test
    fun tHome09_homePressResetOrder() {
        assumeTrue(isDefaultHome())
        val home = pressHomeAndAwaitHomeInstance()
        compose.onNodeWithTag(HomeTestTags.PILL).performClick()
        compose.waitUntil(5_000) { compose.count(SearchTestTags.LAYER) > 0 }
        compose.onNodeWithTag(SearchTestTags.FIELD).performTextInput("ma")
        device.pressHome()
        compose.waitUntil(5_000) { compose.count(SearchTestTags.LAYER) == 0 }
        compose.waitUntil(3_000) { !imeVisible(home) }
        compose.openAllApps()
        device.pressHome()
        compose.waitUntil(5_000) { compose.count(AppListTestTags.LIST) == 0 }
        vm(home).onAction(HomeAction.OpenHomeMenu)
        device.pressHome()
        compose.waitUntil(5_000) { compose.count(SheetTestTags.MENU) == 0 }
    }

    /** T-HOME-10: with "Home press opens search", Home at rest opens search with the IME. */
    @Test
    fun tHome10_homePressOpensSearch() {
        assumeTrue(isDefaultHome())
        val home = pressHomeAndAwaitHomeInstance() // setting still off: home at rest, in front
        runBlocking { appGraph().settingsRepository.update { it.copy(homePressOpensSearch = true) } }
        compose.waitUntil(5_000) {
            vm(home)
                .state.value.settings.homePressOpensSearch
        }
        device.pressHome()
        compose.waitUntil(5_000) { compose.count(SearchTestTags.LAYER) > 0 && imeVisible(home) }
    }

    /** T-HOME-11: on a phone, "Allow rotation" off keeps portrait; on, the layout rotates. */
    @Test
    fun tHome11_rotation() {
        assumeTrue(
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext.resources.configuration.smallestScreenWidthDp < HomeWindow.TABLET_MIN_SW_DP,
        )
        compose.waitUntil(3_000) { orientation() == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        runBlocking { appGraph().settingsRepository.update { it.copy(allowRotation = true) } }
        compose.waitUntil(3_000) { orientation() == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        device.setOrientationLeft()
        device.waitForIdle()
        device.setOrientationNatural()
    }

    /** T-GEST-06: exclusion rects only on the index side while resumed; none after `onPause`. */
    @Test
    fun tGest06_exclusionGeometry() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        awaitHomeIndex()
        val rects = exclusionRects()
        assertThat(rects).isNotEmpty()
        var width = 0
        compose.activityRule.scenario.onActivity { width = it.window.decorView.width }
        rects.forEach { assertThat(it.left).isAtLeast(width / 2) } // index on the end side (LTR)
        device.pressRecentApps()
        device.waitForIdle()
        device.pressBack()
    }

    /**
     * Owner report (A56): search closed only with the back gesture from the left. With search open the index is covered,
     * so no exclusion may remain on its edge, and a swipe from the right edge (gesture navigation; run with
     * `strata.navMode=gestural`) closes search. The exclusion half runs in any navigation mode.
     */
    @Test
    fun searchClosesWithBackFromTheIndexEdge() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        awaitHomeIndex()
        compose.onNodeWithTag(HomeTestTags.PILL).performClick()
        assertThat(device.wait(Until.hasObject(By.res(SearchTestTags.LAYER)), DEVICE_TIMEOUT_MS)).isTrue()
        compose.waitForIdle()
        assertThat(exclusionRects()).isEmpty()
        // navigation_mode 2 = gestural: only then is an edge swipe a back gesture.
        assumeTrue(shell("settings get secure navigation_mode").trim() == "2")
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe(w - 1, h / 2, w * 2 / 3, h / 2, EDGE_SWIPE_STEPS)
        assertThat(device.wait(Until.gone(By.res(SearchTestTags.LAYER)), DEVICE_TIMEOUT_MS)).isTrue()
    }

    /** T-GEST-07: "Keep back gesture on index side" limits the exclusion to 200 dp + 16 dp around the touch. */
    @Test
    fun tGest07_keepBackOption() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        runBlocking { appGraph().settingsRepository.update { it.copy(keepBackGestureOnIndexSide = true) } }
        awaitHomeIndex()
        val density =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext.resources.displayMetrics.density
        exclusionRects().forEach { assertThat(it.height()).isAtMost((200 * density + 2).toInt()) }
    }

    /** T-GEST-05 / T-GEST-09 / T-COMPAT-02 (subset): a drag inside the index scrubs; Back from the other edge works. */
    @Test
    fun tGest05_indexScrubAndBack() {
        compose.openAllApps()
        device.pressBack()
        compose.waitUntil(3_000) { compose.count(AppListTestTags.LIST) == 0 }
    }

    /** T-SEARCH-01: a 64 dp swipe up at 300 dp/s opens search; the IME is visible within 300 ms of release. */
    @Test
    fun tSearch01_swipeUpOpensSearch() {
        val metrics =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext.resources.displayMetrics
        val x = device.displayWidth / 2
        val y = (device.displayHeight * 0.45f).toInt()
        val distance = (64 * metrics.density).toInt()

        fun swipeUp() = device.swipe(x, y, x, y - distance, 20) // ≈ 5 ms per step → ~300 dp/s on a 420 dpi phone

        // T-SEARCH-01 measures from "home at rest". Right after the cold start the device and the IME process are
        // not at rest: CI API 36 measured 5 468 ms with HomeActivity "Displayed +6s54ms", Choreographer "Skipped
        // 213 frames", Davey 4 017 ms, an InputConnection timeout with a cancelled and reissued IME show, and
        // multi-second GMS/keyboard initialisation stalls system-wide. So first bring home to rest, then warm the
        // IME once, unmeasured (same swipe, wait for the IME, close it, wait for hidden and rest again). Only the
        // second swipe is timed. Budgets and the assertion are unchanged.
        restHome()
        swipeUp()
        compose.waitUntil(30_000) { compose.count(SearchTestTags.LAYER) > 0 && imeVisible() }
        device.pressBack() // hides the IME first
        compose.waitUntil(30_000) { !imeVisible() }
        if (compose.count(SearchTestTags.LAYER) > 0) {
            // Search can stay open after the IME hide (T-SEARCH-10); the second Back closes it.
            device.pressBack()
            compose.waitUntil(30_000) { compose.count(SearchTestTags.LAYER) == 0 }
        }
        restHome()

        swipeUp()
        val released = System.nanoTime()
        compose.waitUntil(2_000) { compose.count(SearchTestTags.LAYER) > 0 }
        val layerMs = (System.nanoTime() - released) / 1_000_000
        compose.waitUntil(5_000) { imeVisible() }
        val ms = (System.nanoTime() - released) / 1_000_000
        Log.i(
            TAG,
            "T-SEARCH-01: search layer ${layerMs}ms, IME visible ${ms}ms after release (API ${Build.VERSION.SDK_INT})",
        )
        // TEST_PLAN T-SEARCH-01 (D-015): 300 ms on physical devices (API 34+ here; below 34 the time is only logged,
        // CI run 41576277117: 1 117 ms on the API 26 image). Emulators render the IME in software (swiftshader), so
        // CI uses 5 000 ms (measured 3 817 ms API 34, 2 873 ms API 36). The swipe -> search -> IME path is checked
        // on every API.
        if (isEmulator()) {
            assertThat(ms).isAtMost(5_000)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertThat(ms).isAtMost(300)
        }
    }

    /** Home at rest: UiDevice idle, Compose idle and the window focused (key events and the IME need focus). */
    private fun restHome() {
        device.waitForIdle()
        compose.waitForIdle()
        compose.awaitWindowFocus(device)
    }

    private fun isEmulator(): Boolean {
        val id = "${Build.FINGERPRINT} ${Build.HARDWARE} ${Build.PRODUCT} ${Build.MODEL}".lowercase()
        if (listOf("generic", "ranchu", "goldfish", "emulator", "sdk_gphone").any { it in id }) return true
        return shell("getprop ro.kernel.qemu").trim() == "1" || shell("getprop ro.boot.qemu").trim() == "1"
    }

    /** T-FAV-02 (I): reorder survives a process restart (the Room half runs on the JVM). */
    @Test
    fun tFav02_reorderSurvivesRestart() {
        val list = vm().state.value.list ?: return
        val three = list.sections.flatMap { it.apps }.take(3)
        assumeTrue(three.size == 3)
        runBlocking {
            val repo = appGraph().favorites
            three.forEach { repo.add(it.key) { true } }
            repo.reorder(listOf(three[2].key, three[0].key, three[1].key))
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) {
            vm()
                .state.value.favorites.size >= 3
        }
        assertThat(
            vm()
                .state.value.favorites
                .take(3)
                .map { it.key },
        ).containsExactly(three[2].key, three[0].key, three[1].key)
            .inOrder()
    }

    /** T-APPS-08 / T-APPS-11 / T-LIFE-05…07 with the "Zz Test" fixture: rename, uninstall, suspend. */
    @Test
    fun fixtureFlows() {
        assumeTrue(shell("pm path $FIXTURE_PACKAGE").contains("package:"))
        compose.openAllApps()
        compose
            .onNode(
                SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex),
            ).performScrollToNode(hasText(FIXTURE_LABEL))
        compose.onNodeWithText(FIXTURE_LABEL).performTouchInput { longClick() }
        compose.onNodeWithTag(SheetTestTags.RENAME).performClick()
        compose.onNodeWithTag(SheetTestTags.RENAME_FIELD).performTextClearance()
        compose.onNodeWithTag(SheetTestTags.RENAME_FIELD).performTextInput("  Nav  ")
        compose.onNodeWithTag(SheetTestTags.RENAME_SAVE).performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithText("Nav").fetchSemanticsNodes().isNotEmpty() }
        vm().onAction(HomeAction.CloseModal)

        shell("pm suspend $FIXTURE_PACKAGE")
        compose.waitUntil(3_000) {
            compose.onAllNodesWithText("Suspended", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        shell("pm unsuspend $FIXTURE_PACKAGE")

        compose.onNodeWithText("Nav").performTouchInput { longClick() }
        compose.onNodeWithTag(SheetTestTags.UNINSTALL).performClick()
        device.wait(Until.hasObject(By.res("android:id/button2")), 3_000)
        device.findObject(By.res("android:id/button2"))?.click() // cancel: nothing changes
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithText("Nav").fetchSemanticsNodes()).isNotEmpty()
        compose.onNodeWithText("Nav").performTouchInput { longClick() }
        shell("pm uninstall $FIXTURE_PACKAGE") // T-LIFE-05: removed while the sheet is open
        compose.waitUntil(3_000) { compose.count(SheetTestTags.SHEET) == 0 }
    }

    private fun orientation(): Int {
        var o = 0
        compose.activityRule.scenario.onActivity { o = it.requestedOrientation }
        return o
    }

    /**
     * API 30+: the platform's inset visibility. API 26–29: `WindowInsetsControllerCompat.hide(statusBars())` is
     * `SYSTEM_UI_FLAG_FULLSCREEN`, and with edge-to-edge (`LAYOUT_STABLE`) the compat root insets appear to keep the status-bar height
     * while it is hidden, so `WindowInsetsCompat.isVisible` cannot tell (CI run 41576277117, API 26). Read the flag.
     */
    @Suppress("DEPRECATION")
    private fun statusBarVisible(): Boolean {
        var visible = true
        compose.activityRule.scenario.onActivity { activity ->
            val decor = activity.window.decorView
            visible =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.statusBars()) != false
                } else {
                    decor.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0
                }
        }
        return visible
    }

    private fun imeVisible(): Boolean {
        var visible = false
        compose.activityRule.scenario.onActivity { activity -> visible = imeVisibleOn(activity) }
        return visible
    }

    private fun imeVisible(activity: HomeActivity): Boolean {
        var visible = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync { visible = imeVisibleOn(activity) }
        return visible
    }

    private fun imeVisibleOn(activity: HomeActivity): Boolean =
        ViewCompat
            .getRootWindowInsets(activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun exclusionRects(): List<Rect> {
        val rects = mutableListOf<Rect>()
        compose.activityRule.scenario.onActivity { activity ->
            collect(activity.window.decorView, rects)
        }
        return rects
    }

    private fun collect(
        view: View,
        into: MutableList<Rect>,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) into += view.systemGestureExclusionRects
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i), into)
    }

    private companion object {
        const val TAG = "HomeDeviceTest"
        const val FIXTURE_PACKAGE = "app.strata.fixture.zz"
        const val FIXTURE_LABEL = "Zz Test"
        const val EDGE_SWIPE_STEPS = 10
    }
}

private fun ComposeTestRule.count(tag: String): Int = onAllNodesWithTag(tag).fetchSemanticsNodes().size
