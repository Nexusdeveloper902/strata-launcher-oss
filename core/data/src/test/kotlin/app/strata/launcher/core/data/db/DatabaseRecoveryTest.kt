package app.strata.launcher.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.strata.launcher.core.data.Databases
import app.strata.launcher.core.platformapi.settings.ResetCause
import app.strata.launcher.testing.fakes.FakeResetNotices
import app.strata.launcher.testing.fakes.RecordingLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * FR-LIFE-04 / NFR-REL-02 (Robolectric with real SQLite files): corrupt, truncated and unknown-version
 * databases take the rebuild path (quarantine + banner), never a crash or a silent wipe.
 * T-LIFE-11 proper (truncation while stopped, on a device) is in androidTest.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseRecoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notices = FakeResetNotices()
    private val logger = RecordingLogger()
    private val dbFile get() = context.getDatabasePath(StrataDatabase.FILE_NAME)
    private val quarantine get() = File(context.filesDir, "quarantine")

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            StrataDatabase::class.java,
        )

    @Before
    fun clean() {
        dbFile.parentFile?.listFiles()?.forEach { it.delete() }
        quarantine.deleteRecursively()
    }

    @After
    fun tearDown() = clean()

    private fun open() = Databases.openStrata(context, logger, notices)

    private fun writeOneRow() {
        val db = open()
        runBlocking { db.overrideDao().upsert(AppOverrideEntity("a/a.Main", 0, hidden = true)) }
        db.close()
    }

    @Test
    fun healthyDatabaseOpensWithoutReset() {
        writeOneRow()
        val db = open()
        assertThat(runBlocking { db.overrideDao().all() }).hasSize(1)
        db.close()
        assertThat(notices.causes.value).isEmpty()
    }

    @Test
    fun garbageFileIsQuarantinedAndRebuilt() {
        dbFile.parentFile!!.mkdirs()
        dbFile.writeText("this is not a database at all, just text that is long enough ".repeat(40))
        val db = open()
        assertThat(runBlocking { db.snapshotDao().all() }).isEmpty()
        db.close()
        assertThat(notices.causes.value).containsExactly(ResetCause.DATABASE_CORRUPT)
        assertThat(quarantine.listFiles()!!.map { it.name }.any { it.endsWith(StrataDatabase.FILE_NAME) }).isTrue()
    }

    /** T-LIFE-11 (JVM proxy): `strata.db` truncated to 1 KiB → quarantine, banner, usable empty DB. */
    @Test
    fun tLife11_truncatedFileIsQuarantinedAndRebuilt() {
        writeOneRow()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        RandomAccessFile(dbFile, "rw").use { it.setLength(1024) }
        val db = open()
        assertThat(runBlocking { db.overrideDao().all() }).isEmpty()
        db.close()
        assertThat(notices.causes.value).containsExactly(ResetCause.DATABASE_CORRUPT)
        assertThat(quarantine.listFiles()).isNotEmpty()
    }

    /** T-LIFE-14: an unknown (newer) schema version takes the rebuild path, not a silent destructive wipe. */
    @Test
    fun tLife14_unknownVersionTakesRebuildPath() {
        writeOneRow()
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 99 }
        val db = open()
        assertThat(runBlocking { db.overrideDao().all() }).isEmpty()
        db.close()
        assertThat(notices.causes.value).containsExactly(ResetCause.DATABASE_CORRUPT)
        assertThat(quarantine.listFiles()).isNotEmpty()
    }

    /** T-LIFE-14: schema v1 as exported matches the entities (no migrations exist yet; each new one adds a step here). */
    @Test
    fun tLife14_schemaV1MatchesExport() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 1, true).close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
