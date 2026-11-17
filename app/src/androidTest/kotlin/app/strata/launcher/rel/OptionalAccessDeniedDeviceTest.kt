package app.strata.launcher.rel

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.appGraph
import app.strata.launcher.awaitWindowFocus
import app.strata.launcher.coverForeignActivities
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.feature.search.SearchTestTags
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.openAllApps
import app.strata.launcher.passOnboarding
import app.strata.launcher.shell
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * T-REL-04 (NFR-REL-03): every optional access denied (contacts, notification listener, accessibility), with the home
 * role held (DeviceStateRule). The GMD suite runs in this state as a whole (only OptionalAccessDeviceTest grants access,
 * and it restores the denied state before and after each test), so the P0 journeys of the other device classes are the
 * "role only" half. This class pins the preconditions, so a leaked grant cannot turn the suite into a test of the
 * granted state, and runs the access-sensitive parts of the P0 journeys once more: home, the A-Z list, search with
 * results and no contact section, no badge. The "no role on first run" half is OnboardingDeviceTest and the manual
 * J-01 pass on the A56.
 */
@RunWith(AndroidJUnit4::class)
class OptionalAccessDeniedDeviceTest {
    val compose = createAndroidComposeRule<HomeActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(DeviceStateRule()).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext

    @Before
    fun ready() {
        compose.waitForIdle()
        compose.passOnboarding()
        compose.waitUntil(10_000) { compose.count(HomeTestTags.ALL_APPS) > 0 }
        compose.awaitWindowFocus(device)
    }

    @After
    fun tearDown() {
        var owned: HomeActivity? = null
        runCatching { compose.activityRule.scenario.onActivity { owned = it } }
        compose.coverForeignActivities(HomeActivity::class.java, owned)
    }

    @Test
    fun tRel04_preconditionsAllOptionalAccessDenied() {
        val listeners = shell("settings get secure enabled_notification_listeners")
        assertWithMessage(
            "notification listener access must be off",
        ).that(listeners).doesNotContain(context.packageName)
        val a11y = shell("settings get secure enabled_accessibility_services")
        assertWithMessage("the lock accessibility service must be off").that(a11y).doesNotContain(context.packageName)
        // Whatever the grant, contact search itself must be off: Strata reads contacts only when the user turns it on.
        assertWithMessage("contact search setting must be off")
            .that(
                runBlocking {
                    context.appGraph.settingsRepository.settings
                        .first()
                        .contactsSearchEnabled
                },
            ).isFalse()
        val contactsGranted =
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        // The Orchestrator's clearPackageData (`pm clear` before each test) resets runtime permissions, so a grant left by
        // OptionalAccessDeviceTest (tSearch13; it cannot revoke in-process below API 33) no longer reaches this class
        // (CI run 43563634680, API 26, before clearPackageData). Asserted on every API level.
        assertWithMessage("READ_CONTACTS must not be granted").that(contactsGranted).isFalse()
    }

    @Test
    fun tRel04_p0JourneysWithAccessDenied() {
        // J-01 result: home with its regions; no badge without listener access.
        compose.onNodeWithTag(HomeTestTags.FAVORITES).assertExists()
        // J-02/J-03: the A-Z list with its index.
        compose.openAllApps()
        compose.waitUntil(10_000) { compose.count(AppListTestTags.LIST) > 0 }
        device.pressBack()
        compose.waitUntil(10_000) { compose.count(HomeTestTags.PILL) > 0 }
        // J-04: search finds apps; with contacts denied there is no contact section and no permission prompt.
        compose.onNodeWithTag(HomeTestTags.PILL).performClick()
        compose.waitUntil(10_000) { compose.count(SearchTestTags.FIELD) > 0 }
        compose.onNodeWithTag(SearchTestTags.FIELD).performTextInput("e")
        compose.waitUntil(10_000) { compose.count(SearchTestTags.RESULTS) > 0 }
        assertWithMessage("contact section without the permission")
            .that(compose.count(SearchTestTags.CONTACTS_HEADER))
            .isEqualTo(0)
        assertWithMessage("a system permission dialog appeared")
            .that(device.currentPackageName)
            .isEqualTo(context.packageName)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.count(tag: String): Int =
    onAllNodesWithTag(tag).fetchSemanticsNodes().size
