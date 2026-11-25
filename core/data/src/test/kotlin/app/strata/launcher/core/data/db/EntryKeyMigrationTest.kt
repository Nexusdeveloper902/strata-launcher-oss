package app.strata.launcher.core.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.data.Databases
import app.strata.launcher.core.data.launchlog.LaunchEventEntity
import app.strata.launcher.core.data.launchlog.LaunchLogDatabase
import app.strata.launcher.core.model.EntryKeyMigration
import app.strata.launcher.testing.fakes.FakeResetNotices
import app.strata.launcher.testing.fakes.RecordingLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T-LIFE-16 (FB-019, Robolectric with real SQLite files): rows keyed on Strata's old launcher entry
 * (`settings.SettingsActivity`) move to the new one (`settings.EntryActivity`) when the databases open: the favourite
 * keeps its place, the override (hidden, label, icon) survives, the catalog cache and launch events follow. A row that
 * already exists for the new key wins; other apps are untouched; a second open changes nothing.
 */
@RunWith(AndroidJUnit4::class)
class EntryKeyMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pkg get() = context.packageName
    private val old get() = "$pkg/${EntryKeyMigration.OLD_CLASS}"
    private val oldShort get() = "$pkg/.settings.SettingsActivity"
    private val new get() = "$pkg/${EntryKeyMigration.NEW_CLASS}"
    private val notes = "com.example.notes/.Main"
    private val maps = "com.example.maps/.Main"

    private fun clean() {
        context
            .getDatabasePath(StrataDatabase.FILE_NAME)
            .parentFile
            ?.listFiles()
            ?.forEach { it.delete() }
        File(context.noBackupFilesDir, LaunchLogDatabase.FILE_NAME).delete()
    }

    @Before
    fun before() = clean()

    @After
    fun after() = clean()

    private fun strata() = Databases.openStrata(context, RecordingLogger(), FakeResetNotices())

    @Test
    fun tLife16_favouriteOverrideAndCacheMoveToTheNewEntry() {
        // As the previous version left them (the callback finds nothing on this first, empty open).
        strata().apply {
            runBlocking {
                favoriteDao().replaceAll(listOf(notes to 0L, old to 0L, maps to 0L))
                overrideDao().upsert(
                    AppOverrideEntity(old, 0, hidden = true, customLabel = "Launcher setup", iconKind = "pack"),
                )
                overrideDao().upsert(AppOverrideEntity(notes, 0, customLabel = "Notes"))
                snapshotDao().upsert(listOf(AppSnapshotEntity(old, 0, "Strata", pkg, 0, 0, 1L, 2L, "S")))
            }
            close()
        }
        repeat(2) {
            // The second open proves the move is idempotent.
            val db = strata()
            runBlocking {
                assertThat(db.favoriteDao().all().map { it.component }).containsExactly(notes, new, maps).inOrder()
                val overrides = db.overrideDao().all().associateBy { it.component }
                assertThat(overrides.keys).containsExactly(new, notes)
                assertThat(overrides.getValue(new).customLabel).isEqualTo("Launcher setup")
                assertThat(overrides.getValue(new).hidden).isTrue()
                assertThat(overrides.getValue(new).iconKind).isEqualTo("pack")
                assertThat(overrides.getValue(notes).customLabel).isEqualTo("Notes")
                assertThat(db.snapshotDao().all().map { it.component }).containsExactly(new)
            }
            db.close()
        }
    }

    @Test
    fun anExistingRowForTheNewEntryWins_andTheShortFormMovesToo() {
        strata().apply {
            runBlocking {
                favoriteDao().replaceAll(listOf(oldShort to 0L, new to 0L))
                overrideDao().upsert(AppOverrideEntity(old, 0, customLabel = "Old label"))
                overrideDao().upsert(AppOverrideEntity(new, 0, customLabel = "New label"))
                overrideDao().upsert(AppOverrideEntity(old, 10, customLabel = "Work copy"))
            }
            close()
        }
        val db = strata()
        runBlocking {
            assertThat(db.favoriteDao().all().map { it.component }).containsExactly(new)
            val rows = db.overrideDao().all().associate { (it.component to it.userSerial) to it.customLabel }
            assertThat(rows).containsExactly((new to 0L), "New label", (new to 10L), "Work copy")
        }
        db.close()
    }

    @Test
    fun launchEventsFollowTheNewEntry() {
        Databases.openLaunchLog(context, RecordingLogger()).apply {
            runBlocking {
                dao().insert(LaunchEventEntity(component = old, userSerial = 0, at = 1L))
                dao().insert(LaunchEventEntity(component = notes, userSerial = 0, at = 2L))
            }
            close()
        }
        val db = Databases.openLaunchLog(context, RecordingLogger())
        val rows = db.openHelper.readableDatabase.query("SELECT component FROM launch_event ORDER BY at")
        val components = mutableListOf<String>()
        while (rows.moveToNext()) components += rows.getString(0)
        rows.close()
        db.close()
        assertThat(components).containsExactly(new, notes).inOrder()
    }
}
