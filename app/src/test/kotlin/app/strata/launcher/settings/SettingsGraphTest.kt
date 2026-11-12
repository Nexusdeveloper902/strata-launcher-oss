package app.strata.launcher.settings

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.BuildConfig
import app.strata.launcher.TestAppGraph
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.platformapi.notifications.ListenerAccess
import app.strata.launcher.core.platformapi.system.PermissionState
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.SheetTestTags
import app.strata.launcher.feature.settings.Licences
import app.strata.launcher.feature.settings.SettingsPageTags
import app.strata.launcher.feature.settings.SettingsTestTags
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.testing.fakes.FakeBadgeSource
import app.strata.launcher.testing.fakes.FakeContactsSource
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fakes.FakeSystemIntents
import app.strata.launcher.theme.ThemesActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** The M8 settings graph (Robolectric, levels R/C): T-SET-01 (C half), T-SET-02, T-SET-04 (R half), T-SET-08. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h5000dp")
class SettingsGraphTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun graph(
        badges: FakeBadgeSource = FakeBadgeSource(),
        contacts: FakeContactsSource = FakeContactsSource(),
    ) = TestAppGraph(
        roleController = FakeRoleController(defaultHome = true),
        settingsRepository = FakeSettingsRepository(Settings(onboardingDone = true)),
        badges = badges,
        contacts = contacts,
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

    private fun tapOnRoot(tag: String) {
        compose.onNodeWithTag(SettingsTestTags.ROOT).performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
        idle()
    }

    /** T-SET-02: the root's groups in the FR-SET-01 order (Backup joins in M8 part 2). */
    @Test
    fun tSet02_groupsInOrder() {
        graph()
        open()
        val headings =
            compose
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                .fetchSemanticsNodes()
                .sortedBy { it.boundsInRoot.top }
                .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }
        assertThat(headings)
            .containsExactly(
                "Settings",
                "Home",
                "App list",
                "Search",
                "Gestures",
                "Notifications",
                "Themes",
                "Icons",
                "Apps",
                "Privacy & permissions",
                "About",
            ).inOrder()
    }

    /** T-SET-01 (C half): the home menu (long-press or the pill overflow) and the list end open settings. */
    @Test
    fun tSet01_entryPointsOpenSettings() {
        val g =
            TestAppGraph(
                roleController = FakeRoleController(defaultHome = true),
                settingsRepository = FakeSettingsRepository(Settings(onboardingDone = true)),
            ).install()
        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(ComponentName(context, HomeActivity::class.java))
        val home = Robolectric.buildActivity(HomeActivity::class.java, intent).setup().get()
        idle()
        compose.onNodeWithTag(HomeTestTags.PILL_MENU).performClick()
        idle()
        compose.onNodeWithTag(SheetTestTags.MENU_SETTINGS).performClick()
        idle()
        val started = shadowOf(home).nextStartedActivity
        assertThat(started.component).isEqualTo(ComponentName(context, SettingsActivity::class.java))
        assertThat(started.action).isEqualTo(Intent.ACTION_APPLICATION_PREFERENCES)
        assertThat(g.settingsRepository.value.onboardingDone).isTrue()
        // The system App info gear: APPLICATION_PREFERENCES opens the settings root without routing (T-HOME-13).
        open()
        compose.onNodeWithTag(SettingsTestTags.ROOT).assertExists()
    }

    /** FR-SET-01 Themes group: the row opens the theme library. */
    @Test
    fun themesRowOpensTheLibrary() {
        graph()
        val activity = open().get()
        tapOnRoot(SettingsPageTags.THEMES)
        assertThat(shadowOf(activity).nextStartedActivity.component)
            .isEqualTo(ComponentName(context, ThemesActivity::class.java))
    }

    /**
     * T-SET-04 (R half): contacts granted, listener access not: each optional access with state, purpose and an
     * action; "Strata has no internet access". Actions keep the disclosure before any grant.
     */
    @Test
    fun tSet04_privacyPage() {
        val badges = FakeBadgeSource(systemAccess = ListenerAccess.NOT_GRANTED)
        val contacts = FakeContactsSource(permissionState = PermissionState.GRANTED)
        val g = graph(badges, contacts)
        open()
        tapOnRoot(SettingsPageTags.PRIVACY_ROW)
        compose.onNodeWithTag(SettingsPageTags.PRIVACY_PAGE).assertExists()
        compose.onNodeWithText("Strata has no internet access", substring = true).assertExists()
        compose.onNodeWithText("Not allowed. Counts notifications", substring = true).assertExists()
        compose.onNodeWithText("Allowed. Finds contacts", substring = true).assertExists()
        // Contacts are granted: the action opens App info, where the permission is withdrawn.
        compose.onNodeWithTag(SettingsPageTags.PRIVACY_CONTACTS + "_action").performClick()
        idle()
        assertThat(g.systemIntents.started).contains(FakeSystemIntents.APP_DETAILS)
        // Notification access is not granted: the J-09 disclosure comes first, never the grant page directly.
        compose.onNodeWithTag(SettingsPageTags.PRIVACY_NOTIFICATIONS + "_action").performClick()
        idle()
        compose.onNodeWithTag(SettingsTestTags.BADGE_DISCLOSURE).assertExists()
        assertThat(badges.grantScreensOpened).isEqualTo(0)
    }

    /** T-SET-04 (R half): the system's permission-usage link lands on the Privacy page; Back leaves it. */
    @Test
    fun tSet04_permissionUsageOpensThePrivacyPage() {
        graph()
        val usage =
            Robolectric
                .buildActivity(
                    PermissionUsageActivity::class.java,
                    Intent("android.intent.action.VIEW_PERMISSION_USAGE")
                        .setComponent(ComponentName(context, PermissionUsageActivity::class.java)),
                ).setup()
                .get()
        val forwarded = shadowOf(usage).nextStartedActivity
        assertThat(forwarded.component).isEqualTo(ComponentName(context, SettingsActivity::class.java))
        assertThat(forwarded.action).isEqualTo(SettingsActivity.ACTION_PRIVACY)
        assertThat(forwarded.extras?.keySet().orEmpty()).isEmpty()
        assertThat(usage.isFinishing).isTrue()
        val settings = open(SettingsActivity.ACTION_PRIVACY).get()
        compose.onNodeWithTag(SettingsPageTags.PRIVACY_PAGE).assertExists()
        settings.onBackPressedDispatcher.onBackPressed()
        idle()
        assertThat(settings.isFinishing).isTrue()
    }

    /** T-SET-08: About shows the version and the privacy statement; Licences lists every licences.json entry. */
    @Test
    fun tSet08_aboutAndLicences() {
        graph()
        val activity = open().get()
        tapOnRoot(SettingsPageTags.ABOUT_ROW)
        compose.onNodeWithText("Version ${BuildConfig.VERSION_NAME}").assertExists()
        compose.onNodeWithTag(SettingsPageTags.ABOUT_PRIVACY).assertExists()
        compose.onNodeWithText("collects no data", substring = true).assertExists()
        compose.onNodeWithTag(SettingsPageTags.LICENCES_ROW).performClick()
        idle()
        val entries =
            Licences.parse(
                context.assets.open(Licences.ASSET).use { it.readBytes().toString(Charsets.UTF_8) },
            )
        assertThat(entries.filter { !it.font }).isNotEmpty()
        for (e in entries) {
            compose
                .onNodeWithTag(
                    SettingsPageTags.LICENCES_PAGE,
                ).performScrollToNode(hasTestTag(SettingsPageTags.licence(e)))
            compose.onNodeWithTag(SettingsPageTags.licence(e)).assertExists()
        }
        // No OFL font is bundled yet (VISUAL_SPEC §4.2 catalogue open); the page says so.
        compose.onNodeWithText("No fonts are bundled yet.").assertExists()
        val androidx = entries.first { it.name.startsWith("AndroidX") }
        compose.onNodeWithTag(SettingsPageTags.licence(androidx)).performClick()
        idle()
        compose.onNodeWithTag(SettingsPageTags.LICENCE_TEXT).assertExists()
        compose
            .onAllNodesWithText(
                "Apache License",
                substring = true,
            ).fetchSemanticsNodes()
            .let { assertThat(it).isNotEmpty() }
        // Back: text → licences → about → root.
        repeat(3) {
            activity.onBackPressedDispatcher.onBackPressed()
            idle()
        }
        compose.onNodeWithTag(SettingsTestTags.ROOT).assertExists()
        assertThat((activity as Activity).isFinishing).isFalse()
    }
}
