package app.strata.launcher.settings

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import app.strata.launcher.core.model.BadgeStyle
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.platformapi.notifications.ListenerAccess
import app.strata.launcher.core.platformapi.system.PermissionState
import app.strata.launcher.feature.settings.SettingsTestTags
import app.strata.launcher.testing.fakes.FakeBadgeSource
import app.strata.launcher.testing.fakes.FakeContactsSource
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fakes.FakeSystemIntents
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The opt-in access rows of settings (Robolectric, levels R/C): notification badges (J-09, T-NOTIF-01/02/03/06) and
 * contact search (T-SEARCH-13/14). The system grant page and the permission dialog are replaced by the fakes' seams;
 * the device halves are in `OptionalAccessDeviceTest` and the manual pass.
 */
@RunWith(AndroidJUnit4::class)
class OptionalAccessSettingsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun graph(
        settings: Settings = Settings(onboardingDone = true),
        badges: FakeBadgeSource = FakeBadgeSource(),
        contacts: FakeContactsSource = FakeContactsSource(),
    ) = TestAppGraph(
        settingsRepository = FakeSettingsRepository(settings),
        badges = badges,
        contacts = contacts,
    ).install()

    private fun open(): ActivityController<SettingsActivity> {
        val intent =
            Intent(
                Intent.ACTION_APPLICATION_PREFERENCES,
            ).setComponent(ComponentName(context, SettingsActivity::class.java))
        val controller = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup()
        idle()
        return controller
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(SettingsTestTags.ROOT).performScrollToNode(hasTestTag(tag))
    }

    private fun tap(tag: String) {
        scrollTo(tag)
        compose.onNodeWithTag(tag).performClick()
        idle()
    }

    /** Leaves the activity for the system page and comes back (onPause → onResume). */
    private fun returnFromSystemScreen(controller: ActivityController<SettingsActivity>) {
        controller.pause().stop()
        controller.restart().start().resume()
        idle()
    }

    /** T-NOTIF-01 (R): nothing is asked at first: no disclosure, no grant page, switch off, nothing active. */
    @Test
    fun tNotif01_noPromptsByDefault() {
        val g = graph()
        open()
        scrollTo(SettingsTestTags.BADGES)
        compose.onNodeWithTag(SettingsTestTags.BADGES).assertIsOff()
        compose.onNodeWithTag(SettingsTestTags.BADGE_DISCLOSURE).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTestTags.BADGES_NOT_ALLOWED).assertDoesNotExist()
        assertThat(g.badges.grantScreensOpened).isEqualTo(0)
        assertThat(g.settingsRepository.value.badgesEnabled).isFalse()
    }

    /** T-NOTIF-02 (R): switch → disclosure → Continue → grant page → granted on return → on, style rows shown. */
    @Test
    fun tNotif02_optInAndGrant() {
        val g = graph()
        val controller = open()
        tap(SettingsTestTags.BADGES)
        compose.onNodeWithTag(SettingsTestTags.BADGE_DISCLOSURE).assertExists()
        assertThat(g.badges.grantScreensOpened).isEqualTo(0)
        compose.onNodeWithTag(SettingsTestTags.BADGE_CONTINUE).performClick()
        idle()
        assertThat(g.badges.grantScreensOpened).isEqualTo(1)
        assertThat(g.settingsRepository.value.badgesEnabled).isFalse()

        g.badges.systemAccess = ListenerAccess.GRANTED // the user allowed access on the system page
        returnFromSystemScreen(controller)
        assertThat(g.settingsRepository.value.badgesEnabled).isTrue()
        scrollTo(SettingsTestTags.BADGES)
        compose.onNodeWithTag(SettingsTestTags.BADGES).assertIsOn()
        tap(SettingsTestTags.BADGE_STYLE_COUNT)
        assertThat(g.settingsRepository.value.badgeStyle).isEqualTo(BadgeStyle.COUNT)
    }

    /** J-09: Cancel in the disclosure leaves the switch off and opens nothing. */
    @Test
    fun disclosureCancelChangesNothing() {
        val g = graph()
        open()
        tap(SettingsTestTags.BADGES)
        compose.onNodeWithTag(SettingsTestTags.BADGE_DISCLOSURE).assertExists()
        compose.onNodeWithText("Cancel").performClick()
        idle()
        compose.onNodeWithTag(SettingsTestTags.BADGE_DISCLOSURE).assertDoesNotExist()
        assertThat(g.badges.grantScreensOpened).isEqualTo(0)
        assertThat(g.settingsRepository.value.badgesEnabled).isFalse()
    }

    /** J-09: a return without access → switch off, "Not allowed" with [Open settings]; no nagging otherwise. */
    @Test
    fun grantDenied_notAllowedWithOpenSettings() {
        val g = graph()
        val controller = open()
        tap(SettingsTestTags.BADGES)
        compose.onNodeWithTag(SettingsTestTags.BADGE_CONTINUE).performClick()
        idle()
        returnFromSystemScreen(controller)
        assertThat(g.settingsRepository.value.badgesEnabled).isFalse()
        scrollTo(SettingsTestTags.BADGES_OPEN_ACCESS)
        compose.onNodeWithTag(SettingsTestTags.BADGES_OPEN_ACCESS).performClick()
        idle()
        assertThat(g.badges.grantScreensOpened).isEqualTo(2)
        g.badges.systemAccess = ListenerAccess.GRANTED
        returnFromSystemScreen(controller)
        assertThat(g.settingsRepository.value.badgesEnabled).isTrue()
    }

    /** T-NOTIF-03 (R): access revoked in system settings → on resume the switch is off with "Not allowed". */
    @Test
    fun tNotif03_revokeDetectedOnResume() {
        val badges = FakeBadgeSource(ListenerAccess.CONNECTED)
        val g = graph(settings = Settings(onboardingDone = true, badgesEnabled = true), badges = badges)
        val controller = open()
        scrollTo(SettingsTestTags.BADGES)
        compose.onNodeWithTag(SettingsTestTags.BADGES).assertIsOn()
        badges.systemAccess = ListenerAccess.NOT_GRANTED
        returnFromSystemScreen(controller)
        scrollTo(SettingsTestTags.BADGES)
        compose.onNodeWithTag(SettingsTestTags.BADGES).assertIsOff()
        scrollTo(SettingsTestTags.BADGES_OPEN_ACCESS)
        compose.onNodeWithTag(SettingsTestTags.BADGES_OPEN_ACCESS).assertExists()
        // The setting stays: access granted again later brings the badges back without another opt-in.
        assertThat(g.settingsRepository.value.badgesEnabled).isTrue()
    }

    /** T-NOTIF-06 (R): low-RAM API ≤ 29 → no switch, an explanation instead. */
    @Test
    fun tNotif06_unsupportedShowsExplanation() {
        graph(badges = FakeBadgeSource(ListenerAccess.UNSUPPORTED))
        open()
        scrollTo(SettingsTestTags.BADGES_UNSUPPORTED)
        compose.onNodeWithTag(SettingsTestTags.BADGES_UNSUPPORTED).assertExists()
        compose.onNodeWithTag(SettingsTestTags.BADGES).assertDoesNotExist()
    }

    /** T-SEARCH-13 (R, settings half): already granted → the switch turns on without a dialog. */
    @Test
    fun tSearch13_grantedTurnsOnDirectly() {
        val g = graph(contacts = FakeContactsSource(PermissionState.GRANTED))
        val controller = open()
        tap(SettingsTestTags.CONTACTS)
        compose.onNodeWithTag(SettingsTestTags.CONTACTS_DISCLOSURE).assertDoesNotExist()
        assertThat(shadowOf(controller.get()).lastRequestedPermission).isNull()
        assertThat(g.settingsRepository.value.contactsSearchEnabled).isTrue()
        compose.onNodeWithTag(SettingsTestTags.CONTACTS).assertIsOn()
    }

    /** T-SEARCH-13 (R): disclosure → Continue → `READ_CONTACTS` requested → granted → on. */
    @Test
    fun tSearch13_disclosureThenPermissionGranted() {
        val contacts = FakeContactsSource()
        val g = graph(contacts = contacts)
        val controller = open()
        tap(SettingsTestTags.CONTACTS)
        compose.onNodeWithTag(SettingsTestTags.CONTACTS_CONTINUE).performClick()
        idle()
        val request = shadowOf(controller.get()).lastRequestedPermission
        assertThat(request.requestedPermissions).asList().containsExactly(Manifest.permission.READ_CONTACTS)
        contacts.permissionState = PermissionState.GRANTED
        deliver(controller.get(), request.requestCode, granted = true)
        assertThat(g.settingsRepository.value.contactsSearchEnabled).isTrue()
    }

    /** T-SEARCH-14 (R): denied → switch back to off with "Permission needed" and an App info link. */
    @Test
    fun tSearch14_deniedShowsPermissionNeeded() {
        val g = graph()
        val controller = open()
        tap(SettingsTestTags.CONTACTS)
        compose.onNodeWithTag(SettingsTestTags.CONTACTS_CONTINUE).performClick()
        idle()
        deliver(controller.get(), shadowOf(controller.get()).lastRequestedPermission.requestCode, granted = false)
        assertThat(g.settingsRepository.value.contactsSearchEnabled).isFalse()
        scrollTo(SettingsTestTags.CONTACTS)
        compose.onNodeWithTag(SettingsTestTags.CONTACTS).assertIsOff()
        tap(SettingsTestTags.CONTACTS_PERMISSION_NEEDED)
        assertThat(g.systemIntents.started).contains(FakeSystemIntents.APP_DETAILS)
    }

    /** The request in flight lives in the view model: a recreation between request and answer still applies it. */
    @Test
    fun contactsResultAfterRecreation() {
        val contacts = FakeContactsSource()
        val g = graph(contacts = contacts)
        val controller = open()
        tap(SettingsTestTags.CONTACTS)
        compose.onNodeWithTag(SettingsTestTags.CONTACTS_CONTINUE).performClick()
        idle()
        val code = shadowOf(controller.get()).lastRequestedPermission.requestCode
        controller.recreate()
        idle()
        contacts.permissionState = PermissionState.GRANTED
        deliver(controller.get(), code, granted = true)
        assertThat(g.settingsRepository.value.contactsSearchEnabled).isTrue()
    }

    /** A dialog answer is checked against the platform: "granted" without the permission does not turn it on. */
    @Test
    fun contactsGrantIsVerified() {
        val g = graph()
        val controller = open()
        tap(SettingsTestTags.CONTACTS)
        compose.onNodeWithTag(SettingsTestTags.CONTACTS_CONTINUE).performClick()
        idle()
        deliver(controller.get(), shadowOf(controller.get()).lastRequestedPermission.requestCode, granted = true)
        assertThat(g.settingsRepository.value.contactsSearchEnabled).isFalse()
    }

    @Suppress("DEPRECATION")
    private fun deliver(
        activity: SettingsActivity,
        requestCode: Int,
        granted: Boolean,
    ) {
        activity.onRequestPermissionsResult(
            requestCode,
            arrayOf(Manifest.permission.READ_CONTACTS),
            intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED),
        )
        idle()
    }
}
