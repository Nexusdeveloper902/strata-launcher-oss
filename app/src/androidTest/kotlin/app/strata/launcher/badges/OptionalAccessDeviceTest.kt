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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.appGraph
import app.strata.launcher.bringHomeToFront
import app.strata.launcher.core.model.BadgeKey
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
 * T-SEARCH-12, T-SEARCH-13. Not run in this environment (no emulator); compiled by `assembleDebugAndroidTest`.
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

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(DeviceStateRule()).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val graph get() = context.applicationContext.appGraph
    private val listener = ComponentName(context, BadgeListenerService::class.java).flattenToString()
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
    private val replyThread = HandlerThread("fixture-replies").apply { start() }

    @Before
    fun ready() {
        contactsBefore =
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        listenersBefore = shell("settings get secure enabled_notification_listeners")
        restore()
        compose.passOnboarding()
        compose.waitUntil(10_000) { count(HomeTestTags.ROOT) > 0 }
    }

    @After
    fun tearDown() {
        restore()
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
        fixture(AccessFixtureReceiver.ACTION_CANCEL_ALL)
        fixture(AccessFixtureReceiver.ACTION_CLEAR_CONTACTS)
        revokeListener()
        runBlocking {
            graph.settingsRepository.update {
                it.copy(badgesEnabled = false, contactsSearchEnabled = false, contactsHintShown = false)
            }
        }
    }

    /**
     * `READ_CONTACTS` granted by tSearch13 is given back. Revoking a runtime permission kills the instrumented process,
     * so on API 33+ the app asks for its own revocation once the process ends (`revokeSelfPermissionOnKill`; the
     * orchestrator ends it after every test). Below 33 there is no such call: the grant stays on that emulator. No later
     * test depends on it: every test starts with contact search off, and T-SEARCH-12 only asserts that no dialog appears.
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

    /** Explicit broadcast to the fixture receiver in the test APK (its own process, so its own package and uid). */
    private fun fixture(
        action: String,
        extras: Intent.() -> Unit = {},
    ) {
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
        check(done.await(FIXTURE_TIMEOUT_S, TimeUnit.SECONDS)) { "fixture $action did not answer" }
        check(result.get() == AccessFixtureReceiver.RESULT_DONE) { "fixture $action failed: result ${result.get()}" }
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

    private companion object {
        const val TEST_PACKAGE = "app.strata.launcher.test"
        const val PERMISSION_CONTROLLER = "com.android.permissioncontroller"
        const val GOOGLE_PERMISSION_CONTROLLER = "com.google.android.permissioncontroller"

        /** FR-NOTIF-01: badges appear ≤ 2 s after posting (asserted in tNotif02 only). */
        const val SPEC_BADGE_MS = 2_000L

        /** Upper bound for waits on state; a stall, not a performance budget. */
        const val STALL_MS = 30_000L
        const val SETTLE_MS = 1_500L
        const val QUIET_WAIT_MS = 5_000L
        const val QUERIES = 20
        const val MAX_SCAN_BYTES = 16L * 1024 * 1024
        const val FIXTURE_TIMEOUT_S = 15L
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
