package app.strata.launcher.badges

import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.passOnboarding
import app.strata.launcher.shell
import com.google.common.truth.Truth.assertThat
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

/**
 * Notification badges and contact search on a device (level I): T-NOTIF-01…04, T-NOTIF-07, T-PERF-09 (short form),
 * T-SEARCH-12, T-SEARCH-13. Not run in this environment (no emulator); compiled by `assembleDebugAndroidTest`.
 *
 * The system grant page and the permission dialog cannot be driven on Gradle Managed Devices, so access is granted
 * through the shell (`cmd notification allow_listener`, `pm grant`), the seam the R tests replace with fakes. The fixture
 * app is the test APK ([AccessFixtureReceiver], its own process). Global state is restored in [restore]:
 * listener access, the badge and contact settings, the fixture's notifications and contacts. `READ_CONTACTS` granted
 * by tSearch13 stays granted (revoking a runtime permission kills the instrumented process); the contact setting is
 * turned off again, so the feature is off for later tests.
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

    @Before
    fun ready() {
        listenersBefore = shell("settings get secure enabled_notification_listeners")
        restore()
        compose.passOnboarding()
        compose.waitUntil(10_000) { count(HomeTestTags.ROOT) > 0 }
    }

    @After
    fun tearDown() {
        restore()
        var owned: HomeActivity? = null
        runCatching { compose.activityRule.scenario.onActivity { owned = it } }
        compose.coverForeignActivities(HomeActivity::class.java, owned)
    }

    private fun restore() {
        fixture(AccessFixtureReceiver.ACTION_CANCEL_ALL)
        fixture(AccessFixtureReceiver.ACTION_CLEAR_CONTACTS)
        revokeListener()
        runBlocking {
            graph.settingsRepository.update {
                it.copy(badgesEnabled = false, contactsSearchEnabled = false, contactsHintShown = false)
            }
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
        instrumentation.context.sendBroadcast(intent)
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

    /** Grants listener access through the shell (the user's step on the system page) and turns badges on. */
    private fun grantAndEnableBadges() {
        assumeTrue("listener access unsupported here", graph.badges.access.value != ListenerAccess.UNSUPPORTED)
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
        runBlocking { graph.settingsRepository.update { it.copy(badgesEnabled = true) } }
        compose.waitUntil(10_000) { graph.badges.access.value == ListenerAccess.CONNECTED }
    }

    private fun fixtureBadge(): Int = graph.badges.badges.value[BadgeKey(TEST_PACKAGE, 0L)] ?: 0

    private fun fixtureApp(): VisibleApp {
        var found: VisibleApp? = null
        compose.waitUntil(15_000) {
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
        assertThat(device.currentPackageName).isEqualTo(context.packageName)
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
        val posted = System.currentTimeMillis()
        post(1, AccessFixtureReceiver.KIND_ALERTING)
        compose.waitUntil(BADGE_BUDGET_MS) { fixtureBadge() == 1 }
        assertThat(System.currentTimeMillis() - posted).isAtMost(BADGE_BUDGET_MS)
        post(2, AccessFixtureReceiver.KIND_ALERTING)
        post(3, AccessFixtureReceiver.KIND_ONGOING)
        post(4, AccessFixtureReceiver.KIND_SILENT)
        compose.waitUntil(BADGE_BUDGET_MS) { fixtureBadge() == 2 }
        Thread.sleep(SETTLE_MS) // the ongoing and silent ones must not raise it later
        assertThat(fixtureBadge()).isEqualTo(2)
        instrumentation.runOnMainSync { vm().onAction(HomeAction.OpenList) }
        compose.waitUntil(10_000) {
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
        compose.waitUntil(BADGE_BUDGET_MS) { fixtureBadge() == 1 }
        revokeListener()
        // The resume check (HomeActivity.onResume) re-reads the access; the disconnect also clears the counts.
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) {
            graph.badges.badges.value
                .isEmpty()
        }
        assertThat(graph.badges.access.value.granted).isFalse()
    }

    /** T-NOTIF-07: notification text and an OTP never reach a file of the app (databases, DataStore, files, no_backup, logs). */
    @Test
    fun tNotif07_contentNeverOnDisk() {
        grantAndEnableBadges()
        post(1, AccessFixtureReceiver.KIND_ALERTING, text = SECRET)
        post(2, AccessFixtureReceiver.KIND_ALERTING, text = "Your verification code is $OTP")
        compose.waitUntil(BADGE_BUDGET_MS) { fixtureBadge() == 2 }
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
    }

    /** T-PERF-09 (short form; the 1 h screen-off run is the M9 pass): with the listener on, no job, alarm or wakelock. */
    @Test
    fun tPerf09_noJobsAlarmsOrWakelocks() {
        grantAndEnableBadges()
        post(1, AccessFixtureReceiver.KIND_ALERTING)
        compose.waitUntil(BADGE_BUDGET_MS) { fixtureBadge() == 1 }
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
        compose.waitUntil(5_000) { count(SearchTestTags.FIELD) > 0 }
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
        compose.waitUntil(10_000) { runBlocking { graph.contacts.search("ann", 5) }.size == 5 }
        var owned: HomeActivity? = null
        compose.activityRule.scenario.onActivity { owned = it }
        openSearchAndType("ann")
        compose.waitUntil(5_000) { count(SearchTestTags.CONTACTS_HEADER) > 0 }
        assertThat(count(SearchTestTags.contact(4))).isEqualTo(1)
        assertThat(count(SearchTestTags.contact(5))).isEqualTo(0)
        compose.onNodeWithTag(SearchTestTags.contact(0)).performClick()
        compose.waitUntil(20_000) { device.currentPackageName != context.packageName }
        assertThat(device.currentPackageName).isNotEqualTo(context.packageName)
        compose.bringHomeToFront(owned!!, device)
    }

    private companion object {
        const val TEST_PACKAGE = "app.strata.launcher.test"
        const val PERMISSION_CONTROLLER = "com.android.permissioncontroller"
        const val GOOGLE_PERMISSION_CONTROLLER = "com.google.android.permissioncontroller"
        const val BADGE_BUDGET_MS = 2_000L
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
