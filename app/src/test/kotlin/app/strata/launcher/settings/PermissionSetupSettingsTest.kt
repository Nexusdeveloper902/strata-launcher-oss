package app.strata.launcher.settings

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.SwipeToCall
import app.strata.launcher.core.platformapi.notifications.ListenerAccess
import app.strata.launcher.core.platformapi.system.PermissionState
import app.strata.launcher.feature.settings.SettingsPageTags
import app.strata.launcher.feature.settings.SettingsTestTags
import app.strata.launcher.testing.fakes.FakeBadgeSource
import app.strata.launcher.testing.fakes.FakeContactsSource
import app.strata.launcher.testing.fakes.FakeLockService
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fakes.FakeSystemIntents
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * FR-SEARCH-09 and FR-SET-08 in the real settings activity (Robolectric, levels R/C): T-SET-11 (the "Call directly"
 * choice and its runtime `CALL_PHONE` request), T-SET-12 (the checklist page), T-SET-13 (the restricted-settings guide).
 * The permission dialog and the system pages are the fakes' seams; the device half is `PermissionSetupDeviceTest`.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h5000dp")
class PermissionSetupSettingsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun graph(
        contacts: FakeContactsSource = FakeContactsSource(),
        badges: FakeBadgeSource = FakeBadgeSource(ListenerAccess.NOT_GRANTED),
        role: FakeRoleController = FakeRoleController(defaultHome = false),
    ) = TestAppGraph(
        roleController = role,
        settingsRepository = FakeSettingsRepository(Settings(onboardingDone = true)),
        badges = badges,
        contacts = contacts,
        lockService = FakeLockService(supported = true, enabled = false),
    ).install()

    private fun open(action: String = Intent.ACTION_APPLICATION_PREFERENCES): ActivityController<SettingsActivity> {
        val intent = Intent(action).setComponent(ComponentName(context, SettingsActivity::class.java))
        val controller = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup()
        idle()
        return controller
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun onRoot(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(SettingsTestTags.ROOT).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun onPrivacy(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(SettingsPageTags.PRIVACY_PAGE).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    @Suppress("DEPRECATION")
    private fun deliver(
        activity: SettingsActivity,
        permission: String,
        granted: Boolean,
    ) {
        val request = shadowOf(activity).lastRequestedPermission
        assertThat(request.requestedPermissions).asList().containsExactly(permission)
        activity.onRequestPermissionsResult(
            request.requestCode,
            arrayOf(permission),
            intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED),
        )
        idle()
    }

    /** T-SET-11: nothing is requested on opening; "Call directly" asks for CALL_PHONE; granted → field 25 CALL. */
    @Test
    fun tSet11_callDirectlyGranted() {
        val contacts = FakeContactsSource()
        val g = graph(contacts)
        val controller = open()
        assertThat(shadowOf(controller.get()).lastRequestedPermission).isNull()
        onRoot(SettingsTestTags.swipeToCall(SwipeToCall.DIAL)).assertIsSelected()
        onRoot(SettingsTestTags.swipeToCall(SwipeToCall.CALL)).performClick()
        idle()
        contacts.callPermissionState = PermissionState.GRANTED
        deliver(controller.get(), Manifest.permission.CALL_PHONE, granted = true)
        assertThat(g.settingsRepository.value.swipeToCall).isEqualTo(SwipeToCall.CALL)
        onRoot(SettingsTestTags.swipeToCall(SwipeToCall.CALL)).assertIsSelected()
    }

    /** T-SET-11: denied → the dialer stays, with "Permission needed" and its App info link. */
    @Test
    fun tSet11_callDirectlyDenied() {
        val g = graph()
        val controller = open()
        onRoot(SettingsTestTags.swipeToCall(SwipeToCall.CALL)).performClick()
        idle()
        deliver(controller.get(), Manifest.permission.CALL_PHONE, granted = false)
        assertThat(g.settingsRepository.value.swipeToCall).isEqualTo(SwipeToCall.DIAL)
        onRoot(SettingsTestTags.swipeToCall(SwipeToCall.DIAL)).assertIsSelected()
        onRoot(SettingsTestTags.SWIPE_TO_CALL_PERMISSION_NEEDED).performClick()
        idle()
        assertThat(g.systemIntents.started).contains(FakeSystemIntents.APP_DETAILS)
    }

    /**
     * T-SET-12: the root row counts what is given and opens the checklist; each row has state, purpose and "Allow";
     * the phone row asks for CALL_PHONE, the home row starts the set-default flow, and nothing opens without a tap.
     */
    @Test
    @Config(sdk = [34])
    fun tSet12_checklist() {
        val g = graph()
        val controller = open()
        onRoot(SettingsPageTags.SETUP_ROW).assertExists()
        compose.onNodeWithText("Allowed: 0 of 5").assertExists()
        onRoot(SettingsPageTags.SETUP_ROW).performClick()
        idle()
        compose.onNodeWithTag(SettingsPageTags.PRIVACY_PAGE).assertExists()
        onPrivacy(SettingsPageTags.PRIVACY_COUNT).assertExists()
        for (
        tag in
        listOf(
            SettingsPageTags.PRIVACY_DEFAULT_HOME,
            SettingsPageTags.PRIVACY_NOTIFICATIONS,
            SettingsPageTags.PRIVACY_CONTACTS,
            SettingsPageTags.PRIVACY_PHONE,
            SettingsPageTags.PRIVACY_LOCK,
        )
        ) {
            onPrivacy(tag + "_action").assertExists()
        }
        compose.onNodeWithText("Not allowed. Calls a contact directly", substring = true).assertExists()
        onPrivacy(SettingsPageTags.PRIVACY_LOCK_WHERE).assertExists()
        assertThat(g.badges.grantScreensOpened).isEqualTo(0)
        assertThat(g.systemIntents.started).isEmpty()
        assertThat(shadowOf(controller.get()).lastRequestedPermission).isNull()

        onPrivacy(SettingsPageTags.PRIVACY_PHONE + "_action").performClick()
        idle()
        deliver(controller.get(), Manifest.permission.CALL_PHONE, granted = false)

        onPrivacy(SettingsPageTags.PRIVACY_NOTIFICATIONS + "_action").performClick()
        idle()
        compose.onNodeWithTag(SettingsTestTags.BADGE_CONTINUE).performClick()
        idle()
        assertThat(g.badges.grantScreensOpened).isEqualTo(1)

        onPrivacy(SettingsPageTags.PRIVACY_LOCK + "_action").performClick()
        idle()
        compose.onNodeWithTag(SettingsTestTags.LOCK_CONTINUE).performClick()
        idle()
        assertThat(g.systemIntents.started).contains(FakeSystemIntents.ACCESSIBILITY)

        onPrivacy(SettingsPageTags.PRIVACY_DEFAULT_HOME + "_action").performClick()
        idle()
        assertThat(shadowOf(controller.get()).nextStartedActivityForResult).isNotNull()
    }

    /** T-SET-12: everything given → "Allowed: 5 of 5", no guide, and each action opens where it is withdrawn. */
    @Test
    @Config(sdk = [34])
    fun tSet12_allGiven() {
        val g =
            TestAppGraph(
                roleController = FakeRoleController(defaultHome = true),
                settingsRepository = FakeSettingsRepository(Settings(onboardingDone = true)),
                badges = FakeBadgeSource(ListenerAccess.GRANTED),
                contacts = FakeContactsSource(PermissionState.GRANTED, callPermissionState = PermissionState.GRANTED),
                lockService = FakeLockService(supported = true, enabled = true),
            ).install()
        open(SettingsActivity.ACTION_PRIVACY)
        onPrivacy(SettingsPageTags.PRIVACY_COUNT).assertExists()
        compose.onNodeWithText("Allowed: 5 of 5").assertExists()
        compose.onNodeWithTag(SettingsPageTags.RESTRICTED_GUIDE).assertDoesNotExist()
        compose.onNodeWithTag(SettingsPageTags.PRIVACY_LOCK_WHERE).assertDoesNotExist()
        onPrivacy(SettingsPageTags.PRIVACY_PHONE + "_action").performClick()
        idle()
        assertThat(g.systemIntents.started).containsExactly(FakeSystemIntents.APP_DETAILS)
        onPrivacy(SettingsPageTags.PRIVACY_NOTIFICATIONS + "_action").performClick()
        idle()
        assertThat(g.badges.grantScreensOpened).isEqualTo(1) // the listener page, where access is withdrawn
        compose.onNodeWithTag(SettingsTestTags.BADGE_DISCLOSURE).assertDoesNotExist()
    }

    /** T-SET-13: Android 13+, not installed by a store, notification access off → the guide; its button opens App info. */
    @Test
    @Config(sdk = [34])
    fun tSet13_guideOnApi34() {
        val g = graph()
        open(SettingsActivity.ACTION_PRIVACY)
        onPrivacy(SettingsPageTags.RESTRICTED_GUIDE).assertExists()
        compose.onNodeWithText("choose Allow restricted settings", substring = true).assertExists()
        onPrivacy(SettingsPageTags.RESTRICTED_APP_INFO).performClick()
        idle()
        assertThat(g.systemIntents.started).containsExactly(FakeSystemIntents.APP_DETAILS)
    }

    /** T-SET-13: an app-store install has nothing restricted: no guide. */
    @Test
    @Config(sdk = [34])
    fun tSet13_noGuideForStoreInstall() {
        graph()
        shadowOf(context.packageManager).setInstallSourceInfo(
            context.packageName,
            STORE,
            null,
            null,
            STORE,
            null,
            android.content.pm.PackageInstaller.PACKAGE_SOURCE_STORE,
        )
        open(SettingsActivity.ACTION_PRIVACY)
        onPrivacy(SettingsPageTags.PRIVACY_NOTIFICATIONS).assertExists()
        compose.onNodeWithTag(SettingsPageTags.RESTRICTED_GUIDE).assertDoesNotExist()
    }

    /** T-SET-13: Android 12L has no restricted settings: no guide. */
    @Test
    @Config(sdk = [32])
    fun tSet13_noGuideOnApi32() {
        graph()
        open(SettingsActivity.ACTION_PRIVACY)
        onPrivacy(SettingsPageTags.PRIVACY_NOTIFICATIONS).assertExists()
        compose.onNodeWithTag(SettingsPageTags.RESTRICTED_GUIDE).assertDoesNotExist()
    }

    private companion object {
        /** An invented installer package. */
        const val STORE = "com.example.store"
    }
}
