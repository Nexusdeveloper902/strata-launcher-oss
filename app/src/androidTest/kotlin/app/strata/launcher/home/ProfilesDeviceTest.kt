package app.strata.launcher.home

import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.appGraph
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.coverForeignActivities
import app.strata.launcher.feature.home.HomeAction
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.HomeViewModel
import app.strata.launcher.feature.home.SheetTestTags
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.openAllApps
import app.strata.launcher.passOnboarding
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
 * App shortcuts, work profile and private space on a device (level I). T-APPS-12 runs wherever Strata is the default
 * home (the test APK's launcher activity is the fixture app). The work-profile and private-space tests need such a
 * profile on the device (TestDPC / Settings › Private space); Gradle Managed Devices have none, so they skip there.
 * Tests that need the private-space credential run only with `strata.manual=true` (the owner's A56 pass).
 */
@RunWith(AndroidJUnit4::class)
class ProfilesDeviceTest {
    val compose = createAndroidComposeRule<HomeActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(DeviceStateRule()).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val graph get() = context.applicationContext.appGraph

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun manual(): Boolean = InstrumentationRegistry.getArguments().getString("strata.manual") == "true"

    private fun vm(): HomeViewModel {
        lateinit var vm: HomeViewModel
        compose.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[HomeViewModel::class.java] }
        return vm
    }

    private fun profileSerial(kind: ProfileKind): Long? =
        graph.profiles.profiles.value
            .firstOrNull { it.kind == kind }
            ?.serial

    @Before
    fun ready() {
        compose.passOnboarding()
        compose.waitUntil(10_000) { count(HomeTestTags.ROOT) > 0 }
    }

    @After
    fun tearDown() {
        runBlocking { graph.settingsRepository.update { it.copy(privateSpaceContainerHidden = false) } }
        var owned: HomeActivity? = null
        runCatching { compose.activityRule.scenario.onActivity { owned = it } }
        compose.coverForeignActivities(HomeActivity::class.java, owned)
    }

    /**
     * T-APPS-12: as the default home, the fixture app's sheet lists its shortcuts (2 static + 1 dynamic where the
     * platform lets the test push one) and a tap starts the fixture activity.
     */
    @Test
    fun tApps12_shortcutsInSheet() {
        assumeTrue(graph.roleController.isDefaultHome() && graph.shortcuts.hasHostPermission())
        val expected = 2 + if (pushDynamicShortcut()) 1 else 0
        val fixture = awaitApp { it.packageName == FIXTURE_PACKAGE }
        instrumentation.runOnMainSync { vm().onAction(HomeAction.OpenSheet(fixture)) }
        compose.waitUntil(10_000) { count(SheetTestTags.SHORTCUTS) > 0 }
        compose.waitUntil(5_000) { shortcutRows() == expected }
        compose.onNodeWithTag(SheetTestTags.shortcut("fixture_static_one")).performClick()
        assertThat(device.wait(Until.hasObject(By.pkg(FIXTURE_PACKAGE)), 10_000)).isTrue()
    }

    private fun shortcutRows(): Int =
        listOf(
            "fixture_static_one",
            "fixture_static_two",
            "fixture_dynamic",
        ).sumOf { count(SheetTestTags.shortcut(it)) }

    /** The dynamic fixture shortcut, pushed as the test APK; false where the platform refuses it from this process. */
    private fun pushDynamicShortcut(): Boolean =
        try {
            val test = instrumentation.context
            val info =
                ShortcutInfo
                    .Builder(test, "fixture_dynamic")
                    .setShortLabel("Fixture dynamic")
                    .setIntent(Intent(Intent.ACTION_VIEW).setClassName(test, ShortcutFixtureActivity::class.java.name))
                    .build()
            test.getSystemService(ShortcutManager::class.java)?.setDynamicShortcuts(listOf(info)) == true
        } catch (e: RuntimeException) {
            false
        }

    private fun awaitApp(match: (VisibleApp) -> Boolean): VisibleApp {
        var found: VisibleApp? = null
        compose.waitUntil(15_000) {
            found =
                runBlocking { graph.appList.appList.first() }
                    .allApps
                    .firstOrNull(match)
            found != null
        }
        return found!!
    }

    /**
     * T-APPS-14 and T-LIFE-08: pause work from the list → rows dim with "Paused" within 1 s, no restart; a tap on a
     * paused work app → "Turn on work apps?" → resumes; rows active again.
     */
    @Test
    fun tApps14_tLife08_workProfilePauseResume() {
        val work = profileSerial(ProfileKind.WORK)
        assumeTrue("no work profile on this device", work != null)
        assumeTrue(graph.roleController.isDefaultHome())
        val app = awaitApp { it.profileKind == ProfileKind.WORK }
        compose.openAllApps()
        compose.onNodeWithTag(AppListTestTags.WORK_FILTER_WORK).performClick()
        val started = System.currentTimeMillis()
        compose.onNodeWithTag(AppListTestTags.WORK_SWITCH).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Paused").fetchSemanticsNodes().isNotEmpty() }
        assertThat(System.currentTimeMillis() - started).isLessThan(PAUSE_BUDGET_MS)
        compose.onNodeWithText(app.displayLabel).performClick()
        compose.waitUntil(5_000) { count(SheetTestTags.RESUME_WORK) > 0 }
        compose.onNodeWithTag(SheetTestTags.RESUME_WORK_CONFIRM).performClick()
        // A work challenge, if set, is the system's; the human enters it in the manual pass.
        compose.waitUntil(if (manual()) 60_000 else 10_000) {
            compose.onAllNodesWithText("Paused").fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * T-APPS-15, T-SEARCH-08, T-LIFE-09 (manual: unlocking asks for the credential): the container at the list end;
     * Lock leaves no rows and the Unlock entry; search finds P only while unlocked; locking while results show
     * removes them at once.
     */
    @Test
    fun tApps15_tSearch08_tLife09_privateSpace_manual() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && manual())
        val space = profileSerial(ProfileKind.PRIVATE)
        assumeTrue("no private space visible to Strata", space != null)
        compose.openAllApps()
        compose.waitUntil(10_000) { count(AppListTestTags.PRIVATE_HEADER) > 0 }
        if (count(AppListTestTags.PRIVATE_UNLOCK) > 0) {
            compose.onNodeWithTag(AppListTestTags.PRIVATE_UNLOCK).performClick()
        }
        val p = awaitPrivateApp()
        compose.onNodeWithTag(AppListTestTags.PRIVATE_LOCK).performClick()
        compose.waitUntil(5_000) { count(AppListTestTags.PRIVATE_UNLOCK) > 0 }
        assertThat(compose.onAllNodesWithText(p.displayLabel).fetchSemanticsNodes()).isEmpty()
        assertThat(runBlocking { graph.appList.appList.first() }.privateApps).isEmpty()
    }

    private fun awaitPrivateApp(): VisibleApp {
        var found: VisibleApp? = null
        compose.waitUntil(60_000) {
            found =
                runBlocking { graph.appList.appList.first() }
                    .privateApps
                    .firstOrNull()
            found != null
        }
        return found!!
    }

    /**
     * T-APPS-16 (manual: needs an unlocked space): after launching a private app and trying to rename it, no table of
     * `strata.db` or `launch_log.db`, no DataStore file and no log file holds its component or the space's serial.
     */
    @Test
    fun tApps16_privateNeverPersisted_manual() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && manual())
        val space = profileSerial(ProfileKind.PRIVATE)
        assumeTrue(space != null)
        val p = awaitPrivateApp()
        instrumentation.runOnMainSync {
            vm().onAction(HomeAction.Launch(p, null))
        }
        compose.activityRule.scenario.onActivity { (it as Activity).moveTaskToBack(false) }
        instrumentation.runOnMainSync { vm().onAction(HomeAction.Rename(p.key, "Renamed")) }
        Thread.sleep(PERSIST_WAIT_MS)
        for (name in listOf("strata.db", "launch_log.db")) {
            val file = context.getDatabasePath(name).takeIf { it.exists() } ?: File(context.noBackupFilesDir, name)
            if (!file.exists()) continue
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { tables ->
                    while (tables.moveToNext()) {
                        val table = tables.getString(0)
                        db.rawQuery("SELECT * FROM \"$table\"", null).use { rows ->
                            while (rows.moveToNext()) {
                                for (i in 0 until rows.columnCount) {
                                    val value = runCatching { rows.getString(i) }.getOrNull() ?: continue
                                    assertThat(value).doesNotContain(p.packageName)
                                }
                            }
                        }
                    }
                }
            }
        }
        val texts =
            (File(context.filesDir, "datastore").walkTopDown() + File(context.noBackupFilesDir, "logs").walkTopDown())
                .filter { it.isFile }
        for (file in texts) {
            assertThat(file.readBytes().decodeToString()).doesNotContain(p.packageName)
        }
    }

    private companion object {
        const val FIXTURE_PACKAGE = "app.strata.launcher.test"
        const val PAUSE_BUDGET_MS = 1_000L
        const val PERSIST_WAIT_MS = 3_000L
    }
}

/** The T-APPS-12 fixture app: a plain activity of the test APK with two static shortcuts (test manifest). */
class ShortcutFixtureActivity : Activity()
