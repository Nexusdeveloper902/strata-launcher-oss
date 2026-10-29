package app.strata.launcher.core.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.strata.launcher.core.common.log.Logger
import java.io.File

/**
 * Opens a Room database and never lets a damaged file crash the app (FR-LIFE-04, ARCHITECTURE §10):
 * - a file that is not an SQLite database, or shorter than its header says, is set aside before opening;
 * - an open or migration failure (including an unknown newer version) closes the DB, sets the file aside
 *   and creates an empty one;
 * - SQLite's own corruption callback (which deletes the file) copies it aside first.
 * "Set aside" = moved into [quarantineDir], or deleted when [quarantineDir] is null (disposable stores).
 * [onReset] runs once per reset, for the banner.
 */
class SafeRoomOpener<T : RoomDatabase>(
    private val file: File,
    private val quarantineDir: File?,
    private val logger: Logger,
    private val onReset: () -> Unit,
    private val build: (file: File, factory: SupportSQLiteOpenHelper.Factory) -> T,
) {
    /** Blocking; call on an I/O thread. */
    fun open(): T {
        if (!SqliteHeader.looksIntact(file)) setAside("header check failed")
        val first = build(file, factory())
        return try {
            first.openHelper.writableDatabase // forces creation, migration and validation now
            first
        } catch (e: Exception) {
            logger.e(TAG, "open failed for ${file.name}; rebuilding", e)
            runCatching { first.close() }
            setAside("open failed")
            build(file, factory()).also { it.openHelper.writableDatabase }
        }
    }

    private fun factory(): SupportSQLiteOpenHelper.Factory {
        val framework = FrameworkSQLiteOpenHelperFactory()
        return SupportSQLiteOpenHelper.Factory { config ->
            val inner = config.callback
            val wrapped =
                object : SupportSQLiteOpenHelper.Callback(inner.version) {
                    override fun onConfigure(db: SupportSQLiteDatabase) = inner.onConfigure(db)

                    override fun onCreate(db: SupportSQLiteDatabase) = inner.onCreate(db)

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = inner.onUpgrade(db, oldVersion, newVersion)

                    override fun onDowngrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = inner.onDowngrade(db, oldVersion, newVersion)

                    override fun onOpen(db: SupportSQLiteDatabase) = inner.onOpen(db)

                    override fun onCorruption(db: SupportSQLiteDatabase) {
                        logger.e(TAG, "SQLite reported corruption in ${file.name}")
                        copyAside()
                        onReset()
                        inner.onCorruption(db) // deletes the file; Room then recreates it
                    }
                }
            framework.create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(config.context)
                    .name(config.name)
                    .callback(wrapped)
                    .noBackupDirectory(config.useNoBackupDirectory)
                    .allowDataLossOnRecovery(config.allowDataLossOnRecovery)
                    .build(),
            )
        }
    }

    private fun setAside(reason: String) {
        if (!file.exists()) return
        logger.w(TAG, "setting ${file.name} aside: $reason")
        val dir = quarantineDir
        val stamp = System.currentTimeMillis()
        for (f in companions()) {
            if (!f.exists()) continue
            if (dir == null) {
                f.delete()
            } else {
                dir.mkdirs()
                val target = File(dir, "$stamp-${f.name}")
                if (!f.renameTo(target)) {
                    f.copyTo(target, overwrite = true)
                    f.delete()
                }
            }
        }
        onReset()
    }

    private fun copyAside() {
        val dir = quarantineDir ?: return
        runCatching {
            dir.mkdirs()
            file.copyTo(File(dir, "${System.currentTimeMillis()}-${file.name}"), overwrite = true)
        }
    }

    private fun companions(): List<File> =
        listOf(file) + listOf("-wal", "-shm", "-journal").map { File(file.path + it) }

    private companion object {
        const val TAG = "Db"
    }
}

/** Minimal SQLite file-header sanity check (https://sqlite.org/fileformat.html §1.3). */
internal object SqliteHeader {
    private val MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private const val HEADER_SIZE = 100

    /** True for a missing or empty file (nothing to protect) and for a file whose header is plausible. */
    fun looksIntact(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return true
        if (file.length() < HEADER_SIZE) return false
        val header = ByteArray(HEADER_SIZE)
        file.inputStream().use { if (it.read(header) != HEADER_SIZE) return false }
        if (!header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return false
        val rawPageSize = u16(header, 16)
        val pageSize = if (rawPageSize == 1) 65_536 else rawPageSize
        if (pageSize < 512 || pageSize and (pageSize - 1) != 0) return false
        // "In-header database size" is valid when the change counter equals version-valid-for.
        val pageCount = u32(header, 28)
        val sizeValid = pageCount > 0 && u32(header, 24) == u32(header, 92)
        // A WAL file may hold pages not yet checkpointed, so only a file shorter than the header claims
        // without a WAL beside it is treated as truncated.
        val hasWal = File(file.path + "-wal").let { it.exists() && it.length() > 0 }
        return !(sizeValid && !hasWal && file.length() < pageCount * pageSize)
    }

    private fun u16(
        b: ByteArray,
        at: Int,
    ): Int = (b[at].toInt() and 0xFF shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun u32(
        b: ByteArray,
        at: Int,
    ): Long =
        (b[at].toLong() and 0xFF shl 24) or (b[at + 1].toLong() and 0xFF shl 16) or
            (b[at + 2].toLong() and 0xFF shl 8) or (b[at + 3].toLong() and 0xFF)
}
