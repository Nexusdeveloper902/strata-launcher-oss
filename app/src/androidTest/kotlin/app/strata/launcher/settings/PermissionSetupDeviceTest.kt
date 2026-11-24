package app.strata.launcher.settings

import android.app.Activity
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.strata.launcher.DEVICE_TIMEOUT_MS
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.appGraph
import app.strata.launcher.feature.settings.SettingsPageTags
import app.strata.launcher.feature.settings.SettingsTestTags
import app.strata.launcher.focusReport
import app.strata.launcher.shell
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-SET-14 (level I): the FR-SET-08 checklist's one-tap actions reach the system page that resolves the exact intent:
 * notification access (the listener detail page on API 30+, else the list) and, on API 28+, the accessibility settings
 * for the screen lock. Nothing is granted: the test opens each page and comes back. Whatever the owner had given before
 * stays as it was (a given access opens its withdrawal page without a disclosure; the lock setting is put back).
 */
@RunWith(AndroidJUnit4::class)
class PermissionSetupDeviceTest {
    @get:Rule
    val deviceState = DeviceStateRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val graph get() = context.applicationContext.appGraph
    private val pkg = context.packageName

    /** Numeric user of this process (pm/am/cmd take `--user <id>`; never `current`). */
    private val userId = Process.myUid() / PER_USER_RANGE

    private var lockGestureBefore = false

    private val liveStages = listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED)

    private fun liveSettings(): List<Activity> {
        val found = mutableListOf<Activity>()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            liveStages.flatMapTo(found) { stage ->
                monitor.getActivitiesInStage(stage).filter { it is SettingsActivity }
            }
        }
        return found
    }

    /** Called on the instrumentation thread: [liveSettings] uses `runOnMainSync`, which must not be nested on main. */
    private fun finishSettings() {
        val live = liveSettings()
        instrumentation.runOnMainSync { live.forEach { it.finish() } }
        val end = SystemClock.uptimeMillis() + DEVICE_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < end && liveSettings().isNotEmpty()) SystemClock.sleep(POLL_MS)
    }

    @Before
    fun before() {
        finishSettings()
        lockGestureBefore = runBlocking { graph.settingsRepository.settings.first() }.lockGestureEnabled
    }

    @After
    fun after() {
        // The lock disclosure's Continue writes the setting (field 23); put back what the owner had.
        runBlocking { graph.settingsRepository.update { it.copy(lockGestureEnabled = lockGestureBefore) } }
        finishSettings()
    }

    /** `package` of the activity that resolves [action] for this user, from the package manager; null if none. */
    private fun resolvedPackage(action: String): String? =
        shell("cmd package resolve-activity --brief --user $userId -a $action")
            .lines()
            .map(String::trim)
            .lastOrNull { '/' in it && !it.contains(' ') }
            ?.substringBefore('/')

    private fun openChecklist(): UiObject2 {
        shell("am start --user $userId -n $pkg/.settings.SettingsActivity -a ${SettingsActivity.ACTION_PRIVACY}")
        val page = device.wait(Until.findObject(By.res(SettingsPageTags.PRIVACY_PAGE)), DEVICE_TIMEOUT_MS)
        assertWithMessage("checklist not shown. ${focusReport()}").that(page).isNotNull()
        return page
    }

    /** Scrolls the checklist to [tag] and returns it. */
    private fun row(
        page: UiObject2,
        tag: String,
    ): UiObject2? = device.findObject(By.res(tag)) ?: page.scrollUntil(Direction.DOWN, Until.findObject(By.res(tag)))

    private fun tap(
        page: UiObject2,
        tag: String,
    ) {
        val target = row(page, tag)
        assertWithMessage("$tag not on the checklist. ${focusReport()}").that(target).isNotNull()
        target!!.click()
    }

    /** Continue in the feature's disclosure, if one is shown (only before an access is given). */
    private fun continueDisclosure(
        dialog: String,
        button: String,
        expected: Boolean,
    ) {
        val shown = device.wait(Until.hasObject(By.res(dialog)), if (expected) DEVICE_TIMEOUT_MS else SHORT_MS)
        assertWithMessage("disclosure $dialog shown").that(shown).isEqualTo(expected)
        if (shown) device.findObject(By.res(button)).click()
    }

    private fun awaitFront(
        target: String,
        what: String,
    ) {
        assertWithMessage("$what ($target) did not come to front. ${focusReport()}")
            .that(device.wait(Until.hasObject(By.pkg(target).depth(0)), DEVICE_TIMEOUT_MS))
            .isTrue()
    }

    private fun backToChecklist() {
        device.pressBack()
        assertWithMessage("checklist not shown again. ${focusReport()}")
            .that(device.wait(Until.hasObject(By.res(SettingsPageTags.PRIVACY_PAGE)), DEVICE_TIMEOUT_MS))
            .isTrue()
    }

    /** T-SET-14: notification access: (disclosure, Continue →) the page that resolves the listener intent; no grant. */
    @Test
    fun tSet14_notificationAllowOpensTheListenerPage() {
        val before = runBlocking { graph.badges.refresh() }
        assertWithMessage("listener access is unsupported on this device: the row would be absent")
            .that(before)
            .isNotEqualTo(app.strata.launcher.core.platformapi.notifications.ListenerAccess.UNSUPPORTED)
        val detail =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                resolvedPackage("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
            } else {
                null
            }
        val target = detail ?: resolvedPackage("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        assertWithMessage("no settings activity takes the listener intents").that(target).isNotNull()
        val page = openChecklist()
        tap(page, SettingsPageTags.PRIVACY_NOTIFICATIONS + "_action")
        continueDisclosure(
            SettingsTestTags.BADGE_DISCLOSURE,
            SettingsTestTags.BADGE_CONTINUE,
            expected = !before.granted,
        )
        awaitFront(target!!, "listener settings")
        backToChecklist()
        assertWithMessage("listener access changed by opening the page")
            .that(runBlocking { graph.badges.refresh() }.granted)
            .isEqualTo(before.granted)
    }

    /** T-SET-14: screen lock (API 28+): (disclosure, Continue →) the accessibility settings; below 28 no lock row. */
    @Test
    fun tSet14_lockAllowOpensAccessibility() {
        val page = openChecklist()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // FR-GEST-03 needs API 28: the row must be absent, scrolled to the end.
            assertWithMessage("lock row below API 28")
                .that(row(page, SettingsPageTags.PRIVACY_LOCK))
                .isNull()
            return
        }
        val enabled = graph.lockService.enabled.value
        val target = resolvedPackage("android.settings.ACCESSIBILITY_SETTINGS")
        assertWithMessage("no settings activity takes ACCESSIBILITY_SETTINGS").that(target).isNotNull()
        tap(page, SettingsPageTags.PRIVACY_LOCK + "_action")
        continueDisclosure(SettingsTestTags.LOCK_DISCLOSURE, SettingsTestTags.LOCK_CONTINUE, expected = !enabled)
        awaitFront(target!!, "accessibility settings")
        backToChecklist()
        graph.lockService.refresh()
        assertWithMessage("lock service changed by opening the page")
            .that(graph.lockService.enabled.value)
            .isEqualTo(enabled)
    }

    private companion object {
        const val PER_USER_RANGE = 100_000
        const val POLL_MS = 100L

        /** A disclosure that must not appear is waited for this long (a fraction of the state timeout). */
        const val SHORT_MS = DEVICE_TIMEOUT_MS / 5
    }
}
