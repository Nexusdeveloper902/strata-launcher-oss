package app.strata.launcher.badges

import android.Manifest
import android.app.BroadcastOptions
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.strata.launcher.DEVICE_TIMEOUT_MS
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.appGraph
import app.strata.launcher.awaitSystemSettled
import app.strata.launcher.bringHomeToFront
import app.strata.launcher.core.model.BadgeKey
import app.strata.launcher.core.model.SwipeToCall
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.notifications.ListenerAccess
import app.strata.launcher.core.ui.AppRowTestTags
import app.strata.launcher.coverForeignActivities
import app.strata.launcher.feature.home.HomeAction
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.HomeViewModel
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.feature.search.SearchTestTags
import app.strata.launcher.focusReport
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.passOnboarding
import app.strata.launcher.shell
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import android.os.Process as AndroidProcess

/**
 * Notification badges and contact search on a device (level I): T-NOTIF-01…04, T-NOTIF-07, T-PERF-09 (short form),
 * T-SEARCH-12, T-SEARCH-13, T-SEARCH-27 (swipe to call, under gesture navigation). Not run in this environment (no emulator); compiled by `assembleDebugAndroidTest`.
 *
 * The system grant page and the permission dialog cannot be driven on Gradle Managed Devices, so access is granted
 * through the shell (`cmd notification allow_listener`, `pm grant`), the seam the R tests replace with fakes. The fixture
 * app is the test APK ([AccessFixtureReceiver], its own process), driven by ordered broadcasts whose results the test
 * waits for. Global state is restored synchronously in [restore] before and after every test: listener access, the
 * badge and contact settings, the fixture's notifications and contacts. `READ_CONTACTS` granted by tSearch13 is released
 * by [releaseContactsGrant] (API 33+; see there for older APIs).
 */
@RunWith(AndroidJUnit4::class)
class OptionalAccessDeviceTest {
    val compose = createAndroidComposeRule<HomeActivity>()

    /** T-SEARCH-27 needs gesture navigation (switched before its activity starts, then restored by the rule). */
    @get:Rule
    val rules: RuleChain =
        RuleChain
            .outerRule(
                DeviceStateRule(navModeFor = {
                    if (it.methodName?.startsWith("tSearch27") ==
                        true
                    ) {
                        "gestural"
                    } else {
                        null
                    }
                }),
            ).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val graph get() = context.applicationContext.appGraph
    private val listener = ComponentName(context, BadgeListenerService::class.java).flattenToString()

    /** Numeric user of this process (pm/am/cmd take `--user <id>`; never `current`). */
    private val userId = AndroidProcess.myUid() / PER_USER_RANGE
    private var listenersBefore: String = "null"

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun vm(): HomeViewModel {
        lateinit var vm: HomeViewModel
        compose.activityRule.scenario.onActivity {
            vm = ViewModelProvider(it)[HomeViewModel::class.java]
        }
        return vm
    }

    /** `READ_CONTACTS` of Strata before the test (the owner may have contact search on). */
    private var contactsBefore = false

    /** The settings [restore] resets, as they were before the test (put back in [tearDown]). */
    private var settingsBefore: app.strata.launcher.core.model.Settings? = null
    private val replyThread = HandlerThread("fixture-replies").apply { start() }

    @Before
    fun ready() {
        // The fixture calls below come before passOnboarding's gate: settle first (see awaitSystemSettled).
        awaitSystemSettled()
        contactsBefore =
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        listenersBefore = shell("settings get secure enabled_notification_listeners")
        settingsBefore = runBlocking { graph.settingsRepository.settings.first() }
        restore()
        compose.passOnboarding()
        compose.waitUntil(DEVICE_TIMEOUT_MS) { count(HomeTestTags.ROOT) > 0 }
    }

    @After
    fun tearDown() {
        restore()
        putBackSettings()
        releaseContactsGrant()
        replyThread.quitSafely()
        var owned: HomeActivity? = null
        runCatching { compose.activityRule.scenario.onActivity { owned = it } }
        compose.coverForeignActivities(HomeActivity::class.java, owned)
    }

    /**
     * Puts back every global thing these tests change, synchronously (each fixture step is acknowledged): the
     * fixture's notifications and contacts, listener access, the settings, and `READ_CONTACTS` (see [contactsBefore]).
     */
    private fun restore() {
        // The fixture's own permissions (test APK only); WRITE_CONTACTS is needed to delete its contacts.
        shell("pm grant $TEST_PACKAGE android.permission.WRITE_CONTACTS")
        fixture(AccessFixtureReceiver.ACTION_CANCEL_ALL, resendIfNotDelivered = true)
        fixture(AccessFixtureReceiver.ACTION_CLEAR_CONTACTS, resendIfNotDelivered = true)
        revokeListener()
        runBlocking {
            graph.settingsRepository.update {
                it.copy(
                    badgesEnabled = false,
                    contactsSearchEnabled = false,
                    contactsHintShown = false,
                    swipeToCall = SwipeToCall.DIAL,
                )
            }
        }
    }

    /** The badge, contact-search and swipe-to-call settings the owner had before the test ([restore] cleared them). */
    private fun putBackSettings() {
        val before = settingsBefore ?: return
        runBlocking {
            graph.settingsRepository.update {
                it.copy(
                    badgesEnabled = before.badgesEnabled,
                    contactsSearchEnabled = before.contactsSearchEnabled,
                    contactsHintShown = before.contactsHintShown,
                    swipeToCall = before.swipeToCall,
                )
            }
        }
    }

    /**
     * `READ_CONTACTS` granted by tSearch13 is given back. Revoking a runtime permission kills the instrumented process,
     * so on API 33+ the app asks for its own revocation once the process ends (`revokeSelfPermissionOnKill`; the
     * orchestrator ends it after every test). Below 33 there is no such call, but the Orchestrator's clearPackageData
     * (`pm clear` before every test) resets runtime permissions, so the grant does not reach the next test either.
     */
    private fun releaseContactsGrant() {
        if (contactsBefore || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            context.revokeSelfPermissionOnKill(Manifest.permission.READ_CONTACTS)
        }
    }

    /** Takes listener access away again: `cmd`, or the secure setting as it was before the test (API levels without it). */
    private fun revokeListener() {
        shell("cmd notification disallow_listener $listener")
        if (!shell("settings get secure enabled_notification_listeners").contains(listener)) return
        val before = listenersBefore.split(':').filter { it.isNotEmpty() && it != "null" && it != listener }
        if (before.isEmpty()) {
            shell("settings delete secure enabled_notification_listeners")
        } else {
            shell("settings put secure enabled_notification_listeners " + before.joinToString(":"))
        }
    }

    /**
     * Explicit broadcast to the fixture receiver in the test APK (its own process, so its own package and uid).
     *
     * [resendIfNotDelivered]: only for the idempotent clean-up steps of [restore]. A reply with the sender's initial
     * code ([AccessFixtureReceiver.RESULT_NOT_DELIVERED]) means the receiver never set one: its process died before it
     * answered. GMD API 26 run 44234299843 (tSearch13, the class's first test right after boot): the contacts provider
     * process was replaced while CLEAR_CONTACTS ran, and a client of a dying provider is killed with it. Such a step is
     * sent once more. A step that ran and failed (RESULT_FAILED, RESULT_REFUSED) is never re-sent.
     */
    private fun fixture(
        action: String,
        resendIfNotDelivered: Boolean = false,
        extras: Intent.() -> Unit = {},
    ) {
        val first = sendFixture(action, extras)
        val code =
            if (first == AccessFixtureReceiver.RESULT_NOT_DELIVERED && resendIfNotDelivered) {
                android.util.Log.w("OptionalAccessDeviceTest", "fixture $action not delivered; sending it once more")
                sendFixture(action, extras)
            } else {
                first
            }
        check(code == AccessFixtureReceiver.RESULT_DONE) { "fixture $action failed: result $code" }
    }

    /** Sends one fixture broadcast and returns its result code. */
    private fun sendFixture(
        action: String,
        extras: Intent.() -> Unit,
    ): Int {
        val intent =
            Intent(action)
                .setComponent(ComponentName(TEST_PACKAGE, AccessFixtureReceiver::class.java.name))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .apply(extras)
        val done = CountDownLatch(1)
        val result = AtomicInteger(AccessFixtureReceiver.RESULT_NOT_DELIVERED)
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
        val initial = AccessFixtureReceiver.RESULT_NOT_DELIVERED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Shares Strata's identity, so the fixture can check who drives it (it refuses other senders).
            val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
            context.sendOrderedBroadcast(intent, null, options, reply, handler, initial, null, null)
        } else {
            context.sendOrderedBroadcast(intent, null, reply, handler, initial, null, null)
        }
        check(done.await(DEVICE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "fixture $action did not answer" }
        return result.get()
    }

    private fun post(
        id: Int,
        kind: String,
        text: String = "",
    ) = fixture(AccessFixtureReceiver.ACTION_POST) {
        putExtra(AccessFixtureReceiver.EXTRA_ID, id)
        putExtra(AccessFixtureReceiver.EXTRA_KIND, kind)
        putExtra(AccessFixtureReceiver.EXTRA_TEXT, text)
    }

    /**
     * Turns badges on, then grants listener access through the shell (the user's step on the system page), and waits
     * until the system has bound the listener. Setting first: a listener bound while badges are off is unbound at once.
     */
    private fun grantAndEnableBadges() {
        assumeTrue(
            "listener access unsupported here",
            runBlocking { graph.badges.refresh() } != ListenerAccess.UNSUPPORTED,
        )
        runBlocking { graph.settingsRepository.update { it.copy(badgesEnabled = true) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shell("pm grant $TEST_PACKAGE android.permission.POST_NOTIFICATIONS")
        }
        shell("cmd notification allow_listener $listener")
        if (runBlocking { graph.badges.refresh() } == ListenerAccess.NOT_GRANTED) {
            // Some API levels lack `cmd notification allow_listener`: the secure setting is the same switch.
            val current = shell("settings get secure enabled_notification_listeners").takeUnless { it == "null" }
            shell(
                "settings put secure enabled_notification_listeners " +
                    listOfNotNull(current, listener).joinToString(":"),
            )
        }
        assumeTrue("listener access could not be granted", runBlocking { graph.badges.refresh() }.granted)
        // The bind is asynchronous (system_server); the refresh asks for a rebind while granted and not connected.
        compose.waitUntil(STALL_MS) {
            runBlocking { graph.badges.refresh() } == ListenerAccess.CONNECTED
        }
    }

    private fun fixtureBadge(): Int = graph.badges.badges.value[BadgeKey(TEST_PACKAGE, 0L)] ?: 0

    private fun fixtureApp(): VisibleApp {
        var found: VisibleApp? = null
        compose.waitUntil(STALL_MS) {
            found = runBlocking { graph.appList.appList.first() }.allApps.firstOrNull { it.packageName == TEST_PACKAGE }
            found != null
        }
        return found!!
    }

    /** T-NOTIF-01: after onboarding, home asks for nothing: no disclosure, no grant page, no listener, no badges. */
    @Test
    fun tNotif01_noPromptsAtFirstRun() {
        post(1, AccessFixtureReceiver.KIND_ALERTING)
        Thread.sleep(QUIET_WAIT_MS)
        // Nothing may come to front: no grant page, no disclosure, no dialog (a crash dialog of the fixture also fails it).
        assertWithMessage(focusReport()).that(device.currentPackageName).isEqualTo(context.packageName)
        assertThat(runBlocking { graph.badges.refresh() }.granted).isFalse()
        assertThat(graph.badges.badges.value).isEmpty()
        assertThat(
            compose.onAllNodesWithTag(AppRowTestTags.BADGE_DOT, useUnmergedTree = true).fetchSemanticsNodes(),
        ).isEmpty()
    }

    /** T-NOTIF-02 / T-NOTIF-04: granted and on → the fixture row has a badge ≤ 2 s after posting; 2 alerting + ongoing + silent = 2. */
    @Test
    fun tNotif02_tNotif04_badgeAppearsAndCountsAlertingOnly() {
        grantAndEnableBadges()
        val app = fixtureApp()
        post(1, AccessFixtureReceiver.KIND_ALERTING) // returns once the fixture's notify() returned
        val posted = System.currentTimeMillis()
        compose.waitUntil(STALL_MS) { fixtureBadge() == 1 }
        // FR-NOTIF-01 / T-NOTIF-02: the badge appears ≤ 2 s after posting (the requirement's own budget).
        assertThat(System.currentTimeMillis() - posted).isAtMost(SPEC_BADGE_MS)
        // Ongoing and silent first, an alerting one last: the listener gets them in posting order, so once the count
        // reflects the last one, the first two have been seen (and must not have counted).
        post(3, AccessFixtureReceiver.KIND_ONGOING)
        post(4, AccessFixtureReceiver.KIND_SILENT)
        post(2, AccessFixtureReceiver.KIND_ALERTING)
        compose.waitUntil(STALL_MS) { fixtureBadge() >= 2 }
        assertThat(fixtureBadge()).isEqualTo(2)
        instrumentation.runOnMainSync { vm().onAction(HomeAction.OpenList) }
        // The fixture row ("Strata shortcut fixture") is far down the A–Z list; a lazy list composes only what is on
        // screen, so scroll to it first (GMD API 26, run 42960033351: the off-screen row had no node to find).
        compose.waitUntil(STALL_MS) { count(AppListTestTags.LIST) > 0 }
        compose
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasTestTag(AppListTestTags.row(app)))
        compose.waitUntil(STALL_MS) {
            compose
                .onAllNodes(
                    hasTestTag(AppRowTestTags.BADGE_DOT) and hasAnyAncestor(hasTestTag(AppListTestTags.row(app))),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /** T-NOTIF-03: access revoked in system settings → on return badges are gone. */
    @Test
    fun tNotif03_revokeDetected() {
        grantAndEnableBadges()
        post(1, AccessFixtureReceiver.KIND_ALERTING)
        compose.waitUntil(STALL_MS) { fixtureBadge() == 1 }
        revokeListener()
        // The resume check (HomeActivity.onResume) re-reads the access; the disconnect also clears the counts.
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(STALL_MS) {
            graph.badges.badges.value
                .isEmpty()
        }
        assertThat(graph.badges.access.value.granted).isFalse()
    }

    /** T-NOTIF-07: notification text and an OTP reach no file of the app (databases, DataStore, files, no_backup with the file logs) and no logcat line of its process. */
    @Test
    fun tNotif07_contentNeverOnDisk() {
        grantAndEnableBadges()
        post(1, AccessFixtureReceiver.KIND_ALERTING, text = SECRET)
        post(2, AccessFixtureReceiver.KIND_ALERTING, text = "Your verification code is $OTP")
        compose.waitUntil(STALL_MS) { fixtureBadge() == 2 }
        Thread.sleep(SETTLE_MS) // let DataStore, Room and the log sinks flush anything they would write
        val hits =
            File(context.applicationInfo.dataDir)
                .walkTopDown()
                .filter { it.isFile && it.length() < MAX_SCAN_BYTES }
                .filter { f ->
                    val bytes = runCatching { f.readBytes() }.getOrDefault(ByteArray(0))
                    val text = String(bytes, Charsets.ISO_8859_1)
                    text.contains(SECRET) || text.contains(OTP) ||
                        String(bytes, Charsets.UTF_16LE).let { it.contains(SECRET) || it.contains(OTP) }
                }.map { it.path }
                .toList()
        assertThat(hits).isEmpty()
        // Logs: the file logs are under no_backup (scanned above); logcat of Strata's own process as well.
        val logcat = shell("logcat -d --pid=${AndroidProcess.myPid()}")
        assertThat(logcat).doesNotContain(SECRET)
        assertThat(logcat).doesNotContain(OTP)
    }

    /** T-PERF-09 (short form; the 1 h screen-off run is the M9 pass): with the listener on, no job, alarm or wakelock. */
    @Test
    fun tPerf09_noJobsAlarmsOrWakelocks() {
        grantAndEnableBadges()
        post(1, AccessFixtureReceiver.KIND_ALERTING)
        compose.waitUntil(STALL_MS) { fixtureBadge() == 1 }
        val jobs = context.getSystemService(JobScheduler::class.java).allPendingJobs
        assertThat(jobs).isEmpty()
        val own = context.packageName
        val alarms =
            shell("dumpsys alarm").lineSequence().filter {
                (it.contains("RTC") || it.contains("ELAPSED")) && it.contains(" $own") && !it.contains("$own.test")
            }
        assertThat(alarms.toList()).isEmpty()
        val wakeLocks =
            shell("dumpsys power")
                .substringAfter("Wake Locks:", "")
                .substringBefore("Suspend Blockers:")
                .lineSequence()
                .filter { it.contains(own) && !it.contains("$own.test") }
        assertThat(wakeLocks.toList()).isEmpty()
    }

    private fun openSearchAndType(query: String) {
        instrumentation.runOnMainSync { vm().onAction(HomeAction.OpenSearch()) }
        compose.waitUntil(STALL_MS) { count(SearchTestTags.FIELD) > 0 }
        compose.onNodeWithTag(SearchTestTags.FIELD).performTextClearance()
        compose.onNodeWithTag(SearchTestTags.FIELD).performTextInput(query)
    }

    /** T-SEARCH-12: contacts off (fresh install) → 20 queries, never a permission dialog, never a contact row. */
    @Test
    fun tSearch12_contactsOffNeverPrompts() {
        openSearchAndType("a")
        repeat(QUERIES) { i ->
            compose.onNodeWithTag(SearchTestTags.FIELD).performTextClearance()
            compose.onNodeWithTag(SearchTestTags.FIELD).performTextInput(SAMPLE_QUERIES[i % SAMPLE_QUERIES.size])
            compose.waitForIdle()
            assertThat(device.currentPackageName).isEqualTo(context.packageName)
            assertThat(count(SearchTestTags.CONTACTS_HEADER)).isEqualTo(0)
        }
        assertThat(device.hasObject(By.pkg(PERMISSION_CONTROLLER))).isFalse()
        assertThat(device.hasObject(By.pkg(GOOGLE_PERMISSION_CONTROLLER))).isFalse()
    }

    /**
     * T-SEARCH-13: fixture contacts, contacts on and granted → "ann" shows ≤ 5 contact rows below the apps; the first
     * opens the contacts app. (`READ_CONTACTS` is granted by the shell: the dialog is the manual half.)
     */
    @Test
    fun tSearch13_contactsListedAndOpened() {
        shell("pm grant $TEST_PACKAGE android.permission.WRITE_CONTACTS")
        fixture(AccessFixtureReceiver.ACTION_SEED_CONTACTS) {
            putExtra(AccessFixtureReceiver.EXTRA_NAMES, CONTACT_NAMES)
        }
        shell("pm grant ${context.packageName} android.permission.READ_CONTACTS")
        runBlocking { graph.settingsRepository.update { it.copy(contactsSearchEnabled = true) } }
        compose.waitUntil(STALL_MS) { runBlocking { graph.contacts.search("ann", 5) }.size == 5 }
        var owned: HomeActivity? = null
        compose.activityRule.scenario.onActivity { owned = it }
        openSearchAndType("ann")
        compose.waitUntil(STALL_MS) { count(SearchTestTags.CONTACTS_HEADER) > 0 }
        assertThat(count(SearchTestTags.contact(4))).isEqualTo(1)
        assertThat(count(SearchTestTags.contact(5))).isEqualTo(0)
        compose.onNodeWithTag(SearchTestTags.contact(0)).performClick()
        compose.waitUntil(STALL_MS) { device.currentPackageName != context.packageName }
        assertThat(device.currentPackageName).isNotEqualTo(context.packageName)
        compose.bringHomeToFront(owned!!, device)
    }

    /** Polls [condition] through the compose rule (so its test dispatcher advances); fails with [what] and the focus. */
    private fun awaitOnDevice(
        what: String,
        condition: () -> Boolean,
    ) {
        try {
            compose.waitUntil(DEVICE_TIMEOUT_MS, condition)
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("$what. ${focusReport()}", e)
        }
    }

    /**
     * The activity that takes `ACTION_DIAL` for [number], as `package/class`, from the package manager's own resolution
     * (the shell sees every package); null when nothing resolves. A chooser resolves to the system's resolver activity.
     */
    private fun dialHandler(number: String): String? =
        shell("cmd package resolve-activity --brief --user $userId -a android.intent.action.DIAL -d tel:$number")
            .lines()
            .map(String::trim)
            .lastOrNull { '/' in it && !it.contains(' ') }

    /**
     * T-SEARCH-27: gesture navigation; a fixture contact with an invented number; settings field 25 `DIAL`. A real
     * swipe (through the system input path, so the gesture-navigation edges apply) on the row toward the end edge opens
     * the dialer with the number, or, where no dialer exists, shows the "No phone app" message. Never a call: the mode
     * is `DIAL` and the test checks it before swiping.
     */
    @Test
    fun tSearch27_swipeOpensTheDialer() {
        shell("pm grant --user $userId $TEST_PACKAGE android.permission.WRITE_CONTACTS")
        fixture(AccessFixtureReceiver.ACTION_SEED_CONTACTS) {
            putExtra(AccessFixtureReceiver.EXTRA_NAMES, arrayOf(CALL_NAME))
            putExtra(AccessFixtureReceiver.EXTRA_PHONES, arrayOf(CALL_NUMBER))
        }
        shell("pm grant --user $userId ${context.packageName} android.permission.READ_CONTACTS")
        runBlocking {
            graph.settingsRepository.update { it.copy(contactsSearchEnabled = true, swipeToCall = SwipeToCall.DIAL) }
        }
        assertThat(runBlocking { graph.settingsRepository.settings.first() }.swipeToCall).isEqualTo(SwipeToCall.DIAL)
        compose.waitUntil(DEVICE_TIMEOUT_MS) {
            runBlocking { graph.contacts.search(CALL_QUERY, 5) }.any { it.name == CALL_NAME }
        }
        val hit = runBlocking { graph.contacts.search(CALL_QUERY, 5) }.first { it.name == CALL_NAME }
        assertThat(runBlocking { graph.contacts.numbers(hit) }.map { it.number }).containsExactly(CALL_NUMBER)
        val handler = dialHandler(CALL_DIGITS)
        val noPhoneApp = context.getString(app.strata.launcher.feature.search.R.string.search_call_failed)
        var owned: HomeActivity? = null
        compose.activityRule.scenario.onActivity { owned = it }
        openSearchAndType(CALL_QUERY)
        compose.waitUntil(DEVICE_TIMEOUT_MS) { count(SearchTestTags.contact(0)) > 0 }
        val row = device.wait(Until.findObject(By.res(SearchTestTags.contact(0))), DEVICE_TIMEOUT_MS)
        assertWithMessage("contact row not visible to UiAutomator. ${focusReport()}").that(row).isNotNull()
        val bounds = row.visibleBounds
        val threshold = (CALL_SWIPE_DP * context.resources.displayMetrics.density).toInt()
        val rtl = context.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        // Start well inside the row (never in a system gesture edge) and travel twice the threshold toward the end.
        val startX = if (rtl) bounds.right - bounds.width() / 4 else bounds.left + bounds.width() / 4
        val endX = if (rtl) startX - 2 * threshold else startX + 2 * threshold
        device.swipe(startX, bounds.centerY(), endX, bounds.centerY(), SWIPE_STEPS)
        // Waited through the compose rule, not UiDevice alone: with the v2 rule (#43) the composition's coroutines (the
        // drag-stop that calls, the snackbar) run on a test dispatcher that only advances while the rule syncs. A bare
        // device.wait never let the swipe's call run (CI run 45479694058, API 26/34/36).
        if (handler != null) {
            val pkg = handler.substringBefore('/')
            awaitOnDevice("$handler did not come to front") { device.hasObject(By.pkg(pkg).depth(0)) }
        } else {
            awaitOnDevice("no dialer here, and no message either") { device.hasObject(By.text(noPhoneApp)) }
        }
        assertThat(device.hasObject(By.pkg(PERMISSION_CONTROLLER))).isFalse()
        assertThat(device.hasObject(By.pkg(GOOGLE_PERMISSION_CONTROLLER))).isFalse()
        compose.bringHomeToFront(owned!!, device)
    }

    private companion object {
        const val TEST_PACKAGE = "app.strata.launcher.test"
        const val PER_USER_RANGE = 100_000

        /** T-SEARCH-27 fixture: an invented name and a number from the 555-01xx range reserved for fiction. */
        const val CALL_NAME = "Zed Callfixture"
        const val CALL_QUERY = "callfixture"
        const val CALL_NUMBER = "+1 555 0142"
        const val CALL_DIGITS = "+15550142"

        /** FR-SEARCH-09 threshold (SearchScreen's CALL_SWIPE_THRESHOLD). */
        const val CALL_SWIPE_DP = 96f
        const val SWIPE_STEPS = 40
        const val PERMISSION_CONTROLLER = "com.android.permissioncontroller"
        const val GOOGLE_PERMISSION_CONTROLLER = "com.google.android.permissioncontroller"

        /** FR-NOTIF-01: badges appear ≤ 2 s after posting (asserted in tNotif02 only). */
        const val SPEC_BADGE_MS = 2_000L

        /** Upper bound for waits on state; a stall, not a performance budget. */
        const val STALL_MS = 2 * DEVICE_TIMEOUT_MS
        const val SETTLE_MS = 1_500L
        const val QUIET_WAIT_MS = 5_000L
        const val QUERIES = 20
        const val MAX_SCAN_BYTES = 16L * 1024 * 1024
        const val SECRET = "SECRET-123"
        const val OTP = "482913"
        val SAMPLE_QUERIES = listOf("ann", "bo", "ma", "joanna", "c", "zz", "pe", "li", "sa", "to")
        val CONTACT_NAMES =
            arrayOf(
                "Ann Fixture",
                "Anna Fixture",
                "Annika Fixture",
                "Annette Fixture",
                "Anne Fixture",
                "Annabel Fixture",
                "Bob Fixture",
            )
    }
}
