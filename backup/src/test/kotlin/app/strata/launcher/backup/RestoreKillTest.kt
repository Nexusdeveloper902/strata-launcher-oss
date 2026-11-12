package app.strata.launcher.backup

import app.strata.launcher.backup.BackupHarness.Companion.MAIN
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonParser
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppOverride
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.WidgetPlacement
import app.strata.launcher.core.model.WidgetProviderRef
import app.strata.launcher.core.platformapi.backup.AppData
import app.strata.launcher.core.platformapi.backup.BackupAppStore
import app.strata.launcher.core.platformapi.backup.RestoreFileStore
import app.strata.launcher.core.platformapi.settings.SettingsRepository
import app.strata.launcher.core.theme.store.StoredThemeFile
import app.strata.launcher.testing.fixtures.BackupFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * T-BACKUP-10 (JVM half): the process "dies" (an [Error] nothing catches) at a random write of Replace, 100 runs. After
 * the next start the state is all-old or all-new, never mixed, and the undo snapshot left behind is the old state.
 * The device half (a real kill during Replace) is a manual/instrumented check.
 */
class RestoreKillTest {
    private class Killed : Error("killed")

    /** Counts the writes of one restore and dies at write number [killAt]. */
    private class Switch(
        var killAt: Int = Int.MAX_VALUE,
    ) {
        var writes = 0

        fun tick() {
            if (writes++ == killAt) throw Killed()
        }
    }

    private class KillableApps(
        val inner: BackupAppStore,
        val s: Switch,
    ) : BackupAppStore by inner {
        override suspend fun replace(data: AppData) {
            s.tick()
            inner.replace(data)
        }
    }

    private class KillableFiles(
        val inner: RestoreFileStore,
        val s: Switch,
    ) : RestoreFileStore by inner {
        override fun write(
            name: String,
            bytes: ByteArray,
        ) {
            s.tick()
            inner.write(name, bytes)
        }

        override fun delete(name: String) {
            s.tick()
            inner.delete(name)
        }
    }

    private class KillableSettings(
        val inner: SettingsRepository,
        val s: Switch,
    ) : SettingsRepository {
        override val settings: Flow<Settings> = inner.settings

        override suspend fun update(transform: (Settings) -> Settings) {
            s.tick()
            inner.update(transform)
        }
    }

    private fun tree(
        id: String,
        name: String,
    ) = (JsonParser.parse(BackupFixtures.theme(id, name).toByteArray()) as Outcome.Ok).value as JsonObject

    /** S2 (current) state, with backup B in the documents. */
    private fun harness(): BackupHarness =
        BackupHarness().apply {
            documents.documents["content://b"] = BackupFixtures.validBytes()
            install("com.example.maps/.MainActivity")
            apps.data =
                AppData(
                    overrides = listOf(AppOverride(AppKey("com.z/.Z", MAIN), customLabel = "Zed")),
                    favorites = listOf(AppKey("com.z/.Z", MAIN)),
                    widgets = listOf(WidgetPlacement(7, WidgetProviderRef("com.w/.W", MAIN))),
                )
            val old = "3e4f5061-7283-4d94-8ea5-c6d7e8f9a0b1"
            themes.stored[old] = StoredThemeFile(old, tree(old, "Old"))
            themes.stored[BackupFixtures.THEME_A] =
                StoredThemeFile(BackupFixtures.THEME_A, tree(BackupFixtures.THEME_A, "Old A"))
            themes.activeId = old
            marker.value = settings.value.installId
        }

    private fun service(
        h: BackupHarness,
        s: Switch,
    ) = DefaultBackupService(
        documents = h.documents,
        apps = KillableApps(h.apps, s),
        settings = KillableSettings(h.settings, s),
        themes = h.themes.also { it.beforeWrite = s::tick },
        profiles = h.profiles,
        catalog = h.catalog,
        launchLog = h.launchLog,
        files = KillableFiles(h.files, s),
        marker = h.marker,
        clock = h.clock,
        io = Dispatchers.Unconfined,
        logger = h.logger,
        appVersion = "test",
        zone = { ZoneOffset.UTC },
        profileTimeoutMs = 100,
    )

    @Test
    fun tBackup10_killDuringReplace_isAllOldOrAllNew() =
        runBlocking<Unit> {
            // Reference run: no kill.
            val ref = harness()
            val old = ref.dump()
            val refSwitch = Switch()
            val rs = service(ref, refSwitch)
            rs.restore((rs.preview("content://b") as Outcome.Ok).value.token)
            val new = ref.dump()
            val writes = refSwitch.writes
            assertThat(new).isNotEqualTo(old)
            assertThat(writes).isAtLeast(5)

            val random = Random(10)
            val outcomes = HashMap<String, Int>()
            repeat(100) {
                val h = harness()
                val killAt = random.nextInt(writes)
                val s = Switch(killAt)
                val svc = service(h, s)
                val token = (svc.preview("content://b") as Outcome.Ok).value.token
                val died =
                    try {
                        svc.restore(token)
                        false
                    } catch (e: Killed) {
                        true
                    }
                assertThat(died).isTrue()
                // The snapshot on disk, if any, is the old state.
                h.files.files[DefaultBackupService.UNDO]?.let { bytes ->
                    val undo = RestoreStateCodec.decode(bytes)!!
                    assertThat(undo.apps).isEqualTo(old.apps)
                }
                // Next start (no kill).
                h.themes.beforeWrite = {}
                service(h, Switch()).onProcessStart()
                val after = h.dump()
                val which =
                    when (after) {
                        old -> "old"
                        new -> "new"
                        else -> "mixed at write $killAt: $after"
                    }
                assertThat(which).isAnyOf("old", "new")
                outcomes.merge(which, 1, Int::plus)
                assertThat(h.files.files).isEmpty()
            }
            assertThat(outcomes.keys).containsExactly("old", "new")
        }
}
