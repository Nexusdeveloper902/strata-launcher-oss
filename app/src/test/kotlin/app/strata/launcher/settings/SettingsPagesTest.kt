package app.strata.launcher.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import app.strata.launcher.core.data.Databases
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.AppSection
import app.strata.launcher.core.model.IndexLetter
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.Settings
import app.strata.launcher.feature.search.SearchAction
import app.strata.launcher.feature.search.SearchViewModel
import app.strata.launcher.feature.settings.SettingsTestTags
import app.strata.launcher.feature.settings.Toggle
import app.strata.launcher.testing.fakes.FakeAppListSource
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fakes.RecordingLogger
import app.strata.launcher.testing.fixtures.SearchFixtures.visible
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File

/** Settings pages of M3 (Robolectric, levels R and C): launch-log controls, hidden apps, lock disclosure. */
@RunWith(AndroidJUnit4::class)
class SettingsPagesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val maps = visible("Maps")
    private val music = visible("Music")
    private val hiddenRadio = visible("Radio", hidden = true)
    private val list =
        AppList(
            sections = listOf(AppSection("M", listOf(maps, music))),
            index = listOf(IndexLetter("M", 0, 2)),
            hiddenCount = 1,
            fromSnapshot = false,
            hiddenApps = listOf(hiddenRadio),
        )

    private fun graph(settings: Settings = Settings(onboardingDone = true)) =
        TestAppGraph(settingsRepository = FakeSettingsRepository(settings), appList = FakeAppListSource(list)).install()

    private fun open(action: String) {
        val intent = Intent(action).setComponent(ComponentName(context, SettingsActivity::class.java))
        Robolectric.buildActivity(SettingsActivity::class.java, intent).setup()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun search(g: TestAppGraph) =
        SearchViewModel(
            appList = g.appList,
            settings = g.settingsRepository,
            launchLog = g.launchLog,
            launcher = g.appLauncher,
            catalog = g.appCatalog,
            textFolding = g.textFolding,
            systemIntents = g.systemIntents,
            defaultDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
        )

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(SettingsTestTags.ROOT).performScrollToNode(hasTestTag(tag))
    }

    /** T-SET-07 (C half): Pause stops logging; Resume logs again; Clear empties the log at once. */
    @Test
    fun tSet07_launchLogControls() {
        val g = graph()
        runBlocking { repeat(10) { g.launchLog.record(maps.key, ProfileKind.MAIN) } }
        open(Intent.ACTION_APPLICATION_PREFERENCES)
        scrollTo(SettingsTestTags.PAUSE_HISTORY)
        compose.onNodeWithTag(SettingsTestTags.PAUSE_HISTORY).performClick()
        compose.waitForIdle()
        assertThat(g.launchLog.paused).isTrue()
        runBlocking { repeat(3) { g.launchLog.record(music.key, ProfileKind.MAIN) } }
        assertThat(g.launchLog.events).hasSize(10)
        g.settingsRepository.set(g.settingsRepository.value.copy(launchLogPaused = true)) // what the Room repo stores
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTestTags.PAUSE_HISTORY).performClick()
        compose.waitForIdle()
        assertThat(g.launchLog.paused).isFalse()
        scrollTo(SettingsTestTags.CLEAR_HISTORY)
        compose.onNodeWithTag(SettingsTestTags.CLEAR_HISTORY).performClick()
        compose.waitForIdle()
        assertThat(g.launchLog.events).isEmpty()
    }

    /** T-SEARCH-16: after "Clear history" search shows no recent apps; with recents off, none either. */
    @Test
    fun tSearch16_clearAndDisableRecents() {
        val g = graph()
        runBlocking { repeat(10) { g.launchLog.record(if (it % 2 == 0) maps.key else music.key, ProfileKind.MAIN) } }
        val before = search(g)
        before.onAction(SearchAction.Open())
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(before.state.value.recents).isNotEmpty()

        open(Intent.ACTION_APPLICATION_PREFERENCES)
        scrollTo(SettingsTestTags.CLEAR_HISTORY)
        compose.onNodeWithTag(SettingsTestTags.CLEAR_HISTORY).performClick()
        compose.waitForIdle()
        val after = search(g)
        after.onAction(SearchAction.Open())
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(after.state.value.recents).isEmpty()

        runBlocking { g.launchLog.record(maps.key, ProfileKind.MAIN) }
        scrollTo(SettingsTestTags.toggle(Toggle.RECENTS))
        compose.onNodeWithTag(SettingsTestTags.toggle(Toggle.RECENTS)).performClick()
        compose.waitForIdle()
        assertThat(g.settingsRepository.value.recentsEnabled).isFalse()
        val disabled = search(g)
        disabled.onAction(SearchAction.Open())
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(disabled.state.value.recents).isEmpty()
    }

    /** T-APPS-06 (settings half): Settings → Hidden apps lists the hidden app; "Show in list" unhides it. */
    @Test
    fun tApps06_unhideFromSettings() {
        val g = graph()
        runBlocking { g.overrides.setHidden(hiddenRadio.key, ProfileKind.MAIN, hidden = true) }
        open(SettingsActivity.ACTION_HIDDEN_APPS)
        compose.onNodeWithTag(SettingsTestTags.HIDDEN_APPS_PAGE).assertExists()
        compose.onNodeWithTag(SettingsTestTags.unhide(hiddenRadio)).performClick()
        compose.waitForIdle()
        assertThat(g.overrides.state.value[hiddenRadio.key]).isNull()
    }

    /** FR-GEST-03 / ANDROID_PLATFORM §10: turning the lock on shows the disclosure first; Continue stores it and opens a11y settings. */
    @Test
    fun lockGestureDisclosure() {
        val g = graph()
        open(Intent.ACTION_APPLICATION_PREFERENCES)
        scrollTo(SettingsTestTags.LOCK)
        compose.onNodeWithTag(SettingsTestTags.LOCK).performClick()
        compose.waitForIdle()
        assertThat(g.settingsRepository.value.lockGestureEnabled).isFalse()
        compose.onNodeWithTag(SettingsTestTags.LOCK_CONTINUE).performClick()
        compose.waitForIdle()
        assertThat(g.settingsRepository.value.lockGestureEnabled).isTrue()
        assertThat(g.systemIntents.started).contains("accessibility")
    }

    /** Home and gesture options are stored in settings.pb fields 9–12 and 5 (ARCHITECTURE §4.2). */
    @Test
    fun togglesWriteSettings() {
        val g = graph()
        open(Intent.ACTION_APPLICATION_PREFERENCES)
        for (t in listOf(
            Toggle.HOME_PRESS_OPENS_SEARCH,
            Toggle.HIDE_STATUS_BAR,
            Toggle.ALLOW_ROTATION,
            Toggle.KEEP_BACK_GESTURE,
            Toggle.HAPTICS,
        )) {
            scrollTo(SettingsTestTags.toggle(t))
            compose.onNodeWithTag(SettingsTestTags.toggle(t)).performClick()
            compose.waitForIdle()
        }
        val s = g.settingsRepository.value
        assertThat(s.homePressOpensSearch).isTrue()
        assertThat(s.hideStatusBar).isTrue()
        assertThat(s.allowRotation).isTrue()
        assertThat(s.keepBackGestureOnIndexSide).isTrue()
        assertThat(s.hapticsEnabled).isFalse()
    }

    /** T-PRIV-05 (location half): the log DB is under `noBackupFilesDir`; neither backup rule file includes it. */
    @Test
    fun tPriv05_launchLogLocationAndBackupRules() {
        val db = Databases.openLaunchLog(context, RecordingLogger())
        try {
            val path = File(db.openHelper.writableDatabase.path!!).canonicalFile
            assertThat(path.path).startsWith(context.noBackupFilesDir.canonicalPath)
        } finally {
            db.close()
        }
        val res = File(System.getProperty("user.dir"), "src/main/res/xml")
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val text = File(res, name).readText()
            val includes = Regex("<include[^>]*>").findAll(text).map { it.value }.toList()
            assertThat(includes).isNotEmpty()
            assertThat(includes.none { it.contains("launch_log") || it.contains("no_backup") }).isTrue()
            // Every include names a domain other than no_backup, which the platform always excludes (D-010).
            assertThat(includes.all { it.contains("domain=\"file\"") || it.contains("domain=\"database\"") }).isTrue()
        }
    }
}
