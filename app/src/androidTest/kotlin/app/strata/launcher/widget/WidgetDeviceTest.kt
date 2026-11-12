package app.strata.launcher.widget

import android.app.Activity
import android.app.BroadcastOptions
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.appGraph
import app.strata.launcher.bringHomeToFront
import app.strata.launcher.core.model.WidgetHeights
import app.strata.launcher.core.model.WidgetPlacement
import app.strata.launcher.core.model.WidgetPlacementState
import app.strata.launcher.core.model.WidgetProviderRef
import app.strata.launcher.core.platformapi.widgets.BindStep
import app.strata.launcher.coverForeignActivities
import app.strata.launcher.feature.home.HomeLayer
import app.strata.launcher.feature.home.HomeModal
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.HomeViewModel
import app.strata.launcher.feature.home.SheetTestTags
import app.strata.launcher.feature.widgets.WidgetAreaViewModel
import app.strata.launcher.feature.widgets.WidgetTestTags
import app.strata.launcher.focusReport
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.passOnboarding
import app.strata.launcher.platform.widgets.AndroidWidgetHost
import app.strata.launcher.shell
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Widgets on a device (level I): T-WIDGET-01…09 and T-GEST-02 with the WIDGET_FIX providers of the test APK
 * ([FixtureWidgetProvider], its own process). Not run in this environment (no emulator); compiled by
 * `assembleDebugAndroidTest`.
 *
 * The system consent dialog cannot be driven reliably on Gradle Managed Devices, so binding is allowed through the shell
 * (`appwidget grantbind`), the seam the R tests replace with fakes; only T-WIDGET-03 shows the real dialog and leaves it
 * with Back. Tests that start from a placed widget bind it directly with our host ID (the instrumentation runs in
 * Strata's process). Global state is restored before and after every test: every ID of our host is deleted, the
 * placement removed, the fixture providers enabled, the bind grant revoked, and no picker or fixture activity is left in
 * front.
 */
@RunWith(AndroidJUnit4::class)
class WidgetDeviceTest {
    val compose = createAndroidComposeRule<HomeActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(DeviceStateRule()).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val graph get() = context.applicationContext.appGraph
    private val replyThread = HandlerThread("widget-fixture-replies").apply { start() }

    private val list = ComponentName(TEST_PACKAGE, FixtureWidgetProvider::class.java.name)
    private val backConfig = ComponentName(TEST_PACKAGE, FixtureWidgetProvider.BackConfig::class.java.name)
    private val firstUserConfig = ComponentName(TEST_PACKAGE, FixtureWidgetProvider.FirstUserConfig::class.java.name)
    private val crashConfig = ComponentName(TEST_PACKAGE, FixtureWidgetProvider.CrashConfig::class.java.name)

    /**
     * Nodes with [tag] in the resumed Compose hierarchies; 0 while none is resumed. Between two of our activities (the
     * picker finishing, home not resumed yet) or while a system window is in front (the consent dialog), the rule finds
     * no hierarchy and throws "No compose hierarchies found" instead of reporting nothing: in a wait that means "not
     * yet" (CI run 43204723608: API 26 tWidget03 after Back on the consent dialog, API 36 tWidget09 after Re-add).
     */
    private fun count(tag: String) = nodes { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size }

    private fun countText(text: String) = nodes { compose.onAllNodesWithText(text).fetchSemanticsNodes().size }

    private fun nodes(query: () -> Int): Int =
        try {
            query()
        } catch (e: IllegalStateException) {
            if (e.message?.startsWith("No compose hierarchies found") != true) throw e
            0
        }

    private fun hostIds(): List<Int> = AppWidgetHost(context, AndroidWidgetHost.HOST_ID).appWidgetIds.toList()

    private fun placement(): WidgetPlacement? = runBlocking { graph.widgets.current() }

    @Before
    fun ready() {
        restore()
        compose.passOnboarding()
        compose.waitUntil(10_000) { count(HomeTestTags.ROOT) > 0 }
    }

    @After
    fun tearDown() {
        var owned: HomeActivity? = null
        runCatching { compose.activityRule.scenario.onActivity { owned = it } }
        // A test that failed mid-flow may have left the consent dialog or a fixture configure activity in front: the
        // explicit start of home (singleTask) clears every activity above it in its task.
        // Always: bringHomeToFront returns at once when home is already focused, and otherwise waits until it is.
        owned?.let { home -> compose.bringHomeToFront(home, device) }
        finishPickerAndFixtures()
        restore()
        replyThread.quitSafely()
        compose.coverForeignActivities(HomeActivity::class.java, owned)
    }

    /** Everything these tests change, put back synchronously. */
    private fun restore() {
        val fixtures = listOf(list, backConfig, firstUserConfig, crashConfig)
        fixtures.forEach { setProviderEnabled(it, true) }
        // A provider re-enabled by a failed test comes back only after the delayed PACKAGE_CHANGED.
        compose.waitUntil(STALL_MS) { fixtures.all(::providerInstalled) }
        val host = AppWidgetHost(context, AndroidWidgetHost.HOST_ID)
        host.appWidgetIds.forEach { host.deleteAppWidgetId(it) }
        runBlocking { graph.widgets.current()?.let { graph.widgets.remove(it.appWidgetId) } }
        shell("appwidget revokebind --package ${context.packageName} --user $userId")
    }

    /**
     * The numeric user of the instrumented process. Not "current": on API 26 the `appwidget` command passes
     * `USER_CURRENT` (-2) through to the service, which rejects it ("User -2 must be unlocked for widgets to be
     * available", CI run 43158002484), so the grant never happened.
     */
    private val userId: Int get() = android.os.Process.myUid() / PER_USER_RANGE

    private fun grantBind() {
        shell("appwidget grantbind --package ${context.packageName} --user $userId")
    }

    /** Finishes a picker or fixture activity a failed test may have left (they must not stay in front). */
    private fun finishPickerAndFixtures() {
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED, Stage.STOPPED, Stage.CREATED)
                .flatMap { monitor.getActivitiesInStage(it) }
                .filterIsInstance<WidgetPickerActivity>()
                .forEach(Activity::finish)
        }
        compose.waitUntil(STALL_MS) { !pickerLive() }
    }

    private fun pickerLive(): Boolean {
        var live = false
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            live =
                listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED)
                    .flatMap { monitor.getActivitiesInStage(it) }
                    .any { it is WidgetPickerActivity }
        }
        return live
    }

    /** Ordered broadcast to [WidgetFixtureReceiver]; returns once the fixture confirmed the step. */
    private fun setProviderEnabled(
        provider: ComponentName,
        enabled: Boolean,
    ) = fixture(WidgetFixtureReceiver.ACTION_SET_PROVIDER) {
        putExtra(WidgetFixtureReceiver.EXTRA_CLASS, provider.className)
        putExtra(WidgetFixtureReceiver.EXTRA_ENABLED, enabled)
    }

    private fun fixture(
        action: String,
        extras: Intent.() -> Unit,
    ) {
        val intent =
            Intent(action)
                .setComponent(ComponentName(TEST_PACKAGE, WidgetFixtureReceiver::class.java.name))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .apply(extras)
        val done = CountDownLatch(1)
        val result = AtomicInteger(WidgetFixtureReceiver.RESULT_NOT_DELIVERED)
        val reply =
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context,
                    i: Intent,
                ) {
                    result.set(resultCode)
                    done.countDown()
                }
            }
        val handler = Handler(replyThread.looper)
        val initial = WidgetFixtureReceiver.RESULT_NOT_DELIVERED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
            context.sendOrderedBroadcast(intent, null, options, reply, handler, initial, null, null)
        } else {
            context.sendOrderedBroadcast(intent, null, reply, handler, initial, null, null)
        }
        check(done.await(FIXTURE_TIMEOUT_S, TimeUnit.SECONDS)) { "widget fixture did not answer" }
        check(result.get() == WidgetFixtureReceiver.RESULT_DONE) { "widget fixture failed: ${result.get()}" }
    }

    private fun area(): WidgetAreaViewModel {
        lateinit var vm: WidgetAreaViewModel
        compose.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[WidgetAreaViewModel::class.java] }
        return vm
    }

    private fun home(): HomeViewModel {
        lateinit var vm: HomeViewModel
        compose.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[HomeViewModel::class.java] }
        return vm
    }

    /** Binds [provider] to a new ID of our host (bind grant through the shell) and stores it as the placement. */
    private fun placeDirect(
        provider: ComponentName = list,
        replaceClock: Boolean = true,
        step: Int = WidgetHeights.MIN_STEP,
    ): Int {
        val id = bindInFlight(provider)
        runBlocking { graph.widgets.place(WidgetPlacement(id, ref(provider), step, replaceClock)) }
        graph.widgetHost.settled(id)
        area().refresh()
        compose.waitUntil(STALL_MS) { count(WidgetTestTags.HOST) == 1 }
        return id
    }

    /**
     * Binds [provider] through Strata's own host (bind grant through the shell). The ID stays "in flight" until the test
     * calls `settled`, so home's orphan sweep (onStart, host events) cannot delete it between the bind and the placement.
     */
    private fun bindInFlight(provider: ComponentName): Int {
        grantBind()
        val step = runBlocking { graph.widgetHost.allocateAndBind(ref(provider)) }
        check(step is BindStep.Bound) { "appwidget grantbind did not take effect: $step" }
        return step.id
    }

    private fun providerInstalled(provider: ComponentName): Boolean =
        AppWidgetManager.getInstance(context).installedProviders.any { it.provider == provider }

    private fun ref(provider: ComponentName) = WidgetProviderRef(provider.flattenToString(), 0L)

    private fun openPickerFromHomeMenu() {
        compose.onNodeWithTag(HomeTestTags.PILL_MENU).performClick()
        compose.waitUntil(STALL_MS) { count(SheetTestTags.MENU_WIDGETS) == 1 }
        compose.onNodeWithTag(SheetTestTags.MENU_WIDGETS).performClick()
        compose.waitUntil(STALL_MS) { count(WidgetTestTags.PICKER_LIST) == 1 }
    }

    private fun pick(provider: ComponentName) {
        val tag = scrollTo(WidgetTestTags.provider(provider.flattenToString()))
        compose.onNodeWithTag(tag).performClick()
    }

    /** Scrolls the picker to [tag] and waits until that row is composed (the lazy list may re-lay out meanwhile). */
    private fun scrollTo(tag: String): String {
        compose.onNodeWithTag(WidgetTestTags.PICKER_LIST).performScrollToNode(hasTestTag(tag))
        compose.waitUntil(STALL_MS) { count(tag) == 1 }
        return tag
    }

    private fun widgetOptions() {
        compose
            .onNodeWithTag(WidgetTestTags.HOST)
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .first { it.label == "Widget options" }
            .action()
        compose.waitUntil(STALL_MS) { count(WidgetTestTags.MENU) == 1 }
    }

    private fun a11yAction(label: String) {
        val action =
            compose
                .onNodeWithTag(WidgetTestTags.HOST)
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .first { it.label == label }
                .action
        compose.runOnUiThread { action() }
        compose.waitForIdle()
    }

    /** The focused window is not one of Strata's: the system consent dialog (`ACTION_APPWIDGET_BIND`) is in front. */
    private fun consentDialogFocused(): Boolean {
        val focus = Regex("mCurrentFocus=Window\\{[^}]*\\}").find(focusReport())?.value ?: return false
        return !focus.contains(context.packageName + "/") && !focus.contains("Application Error")
    }

    /** The picker's bind flow is running (set synchronously by the tap that picks). */
    private fun pickerBusy(): Boolean {
        var busy = false
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry
                .getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .plus(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.PAUSED))
                .plus(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STOPPED))
                .filterIsInstance<WidgetPickerActivity>()
                .forEach {
                    busy = busy ||
                        ViewModelProvider(it)[app.strata.launcher.feature.widgets.WidgetPickerViewModel::class.java]
                            .state.value.busy
                }
        }
        return busy
    }

    private fun waitNotAdded() {
        try {
            compose.waitUntil(STALL_MS) { countText("Widget not added") > 0 }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("No \"Widget not added\". ${focusReport()}", e)
        }
    }

    @Test
    fun tWidget01_addWithReplaceClock_thenRemove() {
        grantBind()
        openPickerFromHomeMenu()
        pick(list)
        compose.waitUntil(STALL_MS) { count(WidgetTestTags.CLOCK_REPLACE) == 1 }
        compose.onNodeWithTag(WidgetTestTags.CLOCK_REPLACE).performClick()
        compose.waitUntil(STALL_MS) { !pickerLive() && count(WidgetTestTags.HOST) == 1 }
        assertThat(count(HomeTestTags.CLOCK)).isEqualTo(0)
        val id = placement()!!.appWidgetId
        assertThat(hostIds()).containsExactly(id)
        widgetOptions()
        compose.onNodeWithTag(WidgetTestTags.MENU_REMOVE).performClick()
        compose.waitUntil(STALL_MS) { count(HomeTestTags.CLOCK) == 1 }
        assertThat(count(WidgetTestTags.HOST)).isEqualTo(0)
    }

    @Test
    fun tWidget02_pickerGroupedByApp_withNames() {
        openPickerFromHomeMenu()
        val group = WidgetTestTags.group(TEST_PACKAGE)
        scrollTo(group)
        listOf(list, backConfig, firstUserConfig, crashConfig).forEach { provider ->
            val tag = scrollTo(WidgetTestTags.provider(provider.flattenToString()))
            val description =
                compose
                    .onNodeWithTag(
                        tag,
                    ).fetchSemanticsNode()
                    .config[SemanticsProperties.ContentDescription]
            assertThat(description.single()).contains("widget from")
        }
    }

    @Test
    fun tWidget03_bindDenied_idDeleted() {
        // No grant: the system consent dialog appears and the user declines it.
        openPickerFromHomeMenu()
        pick(list)
        // Wait until the dialog itself holds input focus: the picker loses focus before the dialog window gets it, and
        // a key sent in between is lost (CI run 43208833512, API 26: Back went nowhere and the dialog stayed).
        val end = android.os.SystemClock.uptimeMillis() + STALL_MS
        while (!consentDialogFocused() && android.os.SystemClock.uptimeMillis() < end) device.waitForIdle()
        assertWithMessage("consent dialog focused. ${focusReport()}").that(consentDialogFocused()).isTrue()
        // Decline with the dialog's negative button (button2); Back where the dialog has none.
        val deny = device.findObject(By.res("android", "button2"))
        if (deny != null) deny.click() else device.pressBack()
        waitNotAdded()
        assertThat(hostIds()).isEmpty()
        assertThat(placement()).isNull()
    }

    @Test
    fun tWidget04_configureCancelled_idDeleted() {
        grantBind()
        openPickerFromHomeMenu()
        pick(backConfig)
        val shown =
            device.wait(
                androidx.test.uiautomator.Until
                    .hasObject(By.text(FixtureConfigureActivity.LABEL)),
                STALL_MS,
            )
        assertWithMessage("configure activity shown. ${focusReport()}").that(shown).isTrue()
        device.pressBack()
        waitNotAdded()
        assertThat(hostIds()).isEmpty()
        assertThat(placement()).isNull()
    }

    @Test
    fun tWidget05_configureFirstUserAndCrash_treatedAsCancel() {
        grantBind()
        openPickerFromHomeMenu()
        pick(firstUserConfig)
        compose.waitUntil(STALL_MS) { !pickerBusy() }
        waitNotAdded()
        assertThat(hostIds()).isEmpty()
        pick(crashConfig)
        // Treated as cancel: the ID is freed and nothing is placed. API 26 may show the "has stopped" dialog for the
        // fixture despite hide_error_dialogs. On API 36 the system's crash handling also finishes the picker (the
        // activity below the crashed one in its task, CI run 43161519075); the picker then frees the ID as it finishes.
        // Either way the flow ends: no picker is busy any more.
        compose.waitUntil(STALL_MS) {
            app.strata.launcher.closeSystemErrorDialog(device)
            !pickerBusy() && hostIds().isEmpty()
        }
        assertThat(placement()).isNull()
        // No crash of Strata: its picker or home is in front again.
        compose.waitUntil(STALL_MS) {
            app.strata.launcher.closeSystemErrorDialog(device)
            count(WidgetTestTags.PICKER_LIST) == 1 || app.strata.launcher.resumedHomeActivity() != null
        }
    }

    @Test
    fun tWidget06_resizePersists_withinBounds() {
        placeDirect(step = WidgetHeights.MIN_STEP)
        widgetOptions()
        compose.onNodeWithTag(WidgetTestTags.MENU_RESIZE).performClick()
        compose.waitUntil(STALL_MS) { count(WidgetTestTags.RESIZE_BAR) == 1 }
        repeat(2) {
            compose.onNodeWithTag(WidgetTestTags.TALLER).performClick()
        }
        compose.waitUntil(STALL_MS) { placement()?.heightStep == 3 }
        compose.onNodeWithTag(WidgetTestTags.RESIZE_DONE).performClick()
        // The accessibility action goes one further, and never past step 4.
        a11yAction("Resize taller")
        a11yAction("Resize taller")
        compose.waitUntil(STALL_MS) { placement()?.heightStep == 4 }
        repeat(6) { a11yAction("Resize shorter") }
        compose.waitUntil(STALL_MS) { placement()?.heightStep == 1 }
        repeat(2) { a11yAction("Resize taller") }
        compose.waitUntil(STALL_MS) { placement()?.heightStep == 3 }
        // A new activity (home relaunched) shows step 3 from storage.
        compose.activityRule.scenario.recreate()
        compose.waitUntil(STALL_MS) { count(WidgetTestTags.HOST) == 1 }
        val min = runBlocking { graph.widgetHost.minHeightDp(placement()!!.appWidgetId) }!!
        val config = context.resources.configuration
        val expected = WidgetHeights.heightDp(3, min, config.screenHeightDp)
        val node = compose.onNodeWithTag(WidgetTestTags.HOST).fetchSemanticsNode()
        val heightDp = node.size.height / context.resources.displayMetrics.density
        assertWithMessage("height of step 3").that(abs(heightDp - expected)).isLessThan(2f)
        assertThat(heightDp).isAtLeast(min - 1f)
        assertThat(heightDp).isAtMost(config.screenHeightDp * WidgetHeights.MAX_FRACTION + 1f)
    }

    @Test
    fun tWidget07_removeDeletesId() {
        placeDirect()
        widgetOptions()
        compose.onNodeWithTag(WidgetTestTags.MENU_REMOVE).performClick()
        compose.waitUntil(STALL_MS) { placement() == null && hostIds().isEmpty() }
    }

    @Test
    fun tWidget08_hostRestoredRemap() {
        val newId = bindInFlight(list)
        val oldId = newId + OLD_ID_OFFSET
        runBlocking { graph.widgets.place(WidgetPlacement(oldId, ref(list), replaceClock = true)) }
        WidgetHostRestoredReceiver().onReceive(context, restored(intArrayOf(oldId), intArrayOf(newId)))
        compose.waitUntil(STALL_MS) { placement()?.appWidgetId == newId }
        graph.widgetHost.settled(newId)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(STALL_MS) { count(WidgetTestTags.HOST) == 1 }
        assertThat(placement()!!.state).isEqualTo(WidgetPlacementState.BOUND)
        // An old ID without a new one: the placeholder.
        WidgetHostRestoredReceiver().onReceive(context, restored(intArrayOf(newId), intArrayOf(0)))
        compose.waitUntil(STALL_MS) { count(WidgetTestTags.PLACEHOLDER) == 1 }
    }

    private fun restored(
        old: IntArray,
        new: IntArray,
    ) = Intent(AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED)
        .putExtra(AppWidgetManager.EXTRA_HOST_ID, AndroidWidgetHost.HOST_ID)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS, old)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new)

    @Test
    fun tWidget09_providerLost_placeholder_reAdd() {
        val oldId = placeDirect()
        setProviderEnabled(list, false)
        compose.waitUntil(STALL_MS) {
            area().refresh()
            count(WidgetTestTags.PLACEHOLDER) == 1
        }
        setProviderEnabled(list, true)
        // The component change reaches the widget service through a delayed PACKAGE_CHANGED (about 10 s on API 26):
        // Re-add only once the provider is installed again.
        compose.waitUntil(STALL_MS) { providerInstalled(list) }
        compose.onNodeWithTag(WidgetTestTags.READD).performClick()
        compose.waitUntil(STALL_MS) { !pickerLive() && count(WidgetTestTags.HOST) == 1 }
        val p = placement()!!
        assertThat(p.provider).isEqualTo(ref(list))
        assertThat(p.state).isEqualTo(WidgetPlacementState.BOUND)
        assertThat(p.appWidgetId).isNotEqualTo(oldId)
        assertThat(hostIds()).containsExactly(p.appWidgetId)
    }

    @Test
    fun tGest02_widgetOwnsTouches() {
        placeDirect(step = WidgetHeights.MAX_STEP)
        assertWithMessage(
            "fixture rows",
        ).that(
            device.wait(
                androidx.test.uiautomator.Until
                    .hasObject(By.text("Fixture row 0")),
                STALL_MS,
            ),
        ).isTrue()
        val bounds = compose.onNodeWithTag(WidgetTestTags.HOST).fetchSemanticsNode().boundsInWindow
        val x = bounds.center.x.toInt()
        val bottom = (bounds.bottom - bounds.height * 0.1f).toInt()
        val top = (bounds.top + bounds.height * 0.1f).toInt()
        // Swipe up inside the widget: the list scrolls, search does not open, no home menu.
        device.swipe(x, bottom, x, top, SWIPE_STEPS)
        compose.waitUntil(STALL_MS) { !device.hasObject(By.text("Fixture row 0")) }
        assertThat(home().state.value.layer).isNotEqualTo(HomeLayer.SEARCH)
        assertThat(home().state.value.modal).isEqualTo(HomeModal.None)
        // A slow drag (pressed and scrolling for longer than a long-press) opens no menu.
        device.swipe(x, top, x, bottom, SLOW_SWIPE_STEPS)
        assertThat(home().state.value.modal).isEqualTo(HomeModal.None)
        assertThat(area().state.value.menuOpen).isFalse()
        assertThat(home().state.value.layer).isNotEqualTo(HomeLayer.SEARCH)
        // A press held still inside the widget opens the widget menu, never the home menu.
        device.findObject(By.textStartsWith("Fixture row")).longClick()
        compose.waitUntil(STALL_MS) { area().state.value.menuOpen }
        assertThat(home().state.value.modal).isEqualTo(HomeModal.None)
        compose.waitUntil(STALL_MS) { count(WidgetTestTags.MENU) == 1 }
    }

    /**
     * Review of PR #25 item 1: after home was stopped and started again (`stopListening` / `startListening`), the kept
     * host view still receives the provider's updates.
     */
    @Test
    fun widgetUpdatesArriveAfterStopAndStart() {
        placeDirect()
        assertWithMessage("fixture title")
            .that(
                device.wait(
                    androidx.test.uiautomator.Until
                        .hasObject(By.text("Fixture title")),
                    STALL_MS,
                ),
            ).isTrue()
        lateinit var owned: HomeActivity
        compose.activityRule.scenario.onActivity { owned = it }
        val target = instrumentation.context
        target.startActivity(
            Intent()
                .setClassName(
                    target,
                    app.strata.launcher.CoverActivity::class.java.name,
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        compose.waitUntil(STALL_MS) {
            var stopped = false
            instrumentation.runOnMainSync {
                stopped = ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(owned) == Stage.STOPPED
            }
            stopped
        }
        compose.bringHomeToFront(owned, device)
        fixture(WidgetFixtureReceiver.ACTION_SET_TITLE) { putExtra(WidgetFixtureReceiver.EXTRA_TEXT, UPDATED_TITLE) }
        assertWithMessage("update after stop/start")
            .that(
                device.wait(
                    androidx.test.uiautomator.Until
                        .hasObject(By.text(UPDATED_TITLE)),
                    STALL_MS,
                ),
            ).isTrue()
    }

    private companion object {
        const val TEST_PACKAGE = "app.strata.launcher.test"
        const val UPDATED_TITLE = "Fixture title updated"

        /** `UserHandle.PER_USER_RANGE`: uid = user × 100 000 + app id. */
        const val PER_USER_RANGE = 100_000
        const val STALL_MS = 30_000L
        const val FIXTURE_TIMEOUT_S = 15L
        const val OLD_ID_OFFSET = 1_000
        const val SWIPE_STEPS = 20

        /** About 5 ms per step: 300 steps keep the finger down well past the long-press timeout. */
        const val SLOW_SWIPE_STEPS = 300
    }
}
