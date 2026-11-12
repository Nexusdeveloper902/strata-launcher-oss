package app.strata.launcher.core.data.launchlog

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * `noBackupFilesDir/launch_log.db` version 1 (ARCHITECTURE §4, §4.1). Separate from `strata.db` so it is
 * never part of Auto Backup or device transfer (NFR-PRIV-05, D-010). Disposable: corruption → recreated
 * empty without a banner.
 */
@Database(entities = [LaunchEventEntity::class], version = LaunchLogDatabase.VERSION, exportSchema = true)
abstract class LaunchLogDatabase : RoomDatabase() {
    abstract fun dao(): LaunchEventDao

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "launch_log.db"
    }
}

@Entity(
    tableName = "launch_event",
    indices = [Index(value = ["component", "user_serial"]), Index(value = ["at"])],
)
data class LaunchEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val component: String,
    @ColumnInfo(name = "user_serial") val userSerial: Long,
    /** Epoch ms. */
    val at: Long,
)

data class KeyCount(
    val component: String,
    @ColumnInfo(name = "user_serial") val userSerial: Long,
    val n: Int,
)

/** Launches of one key on one UTC day ([day] = epoch day). */
data class KeyDayCount(
    val component: String,
    @ColumnInfo(name = "user_serial") val userSerial: Long,
    val day: Long,
    val n: Int,
)

data class KeyLast(
    val component: String,
    @ColumnInfo(name = "user_serial") val userSerial: Long,
    @ColumnInfo(name = "last_at") val lastAt: Long,
)

@Dao
interface LaunchEventDao {
    @Insert
    suspend fun insert(event: LaunchEventEntity)

    @Query("DELETE FROM launch_event WHERE at < :cutoff")
    suspend fun deleteBefore(cutoff: Long): Int

    @Query("DELETE FROM launch_event")
    suspend fun deleteAll()

    @Query(
        "SELECT component, user_serial, MAX(at) AS last_at FROM launch_event WHERE at >= :since " +
            "GROUP BY component, user_serial ORDER BY last_at DESC LIMIT :limit",
    )
    suspend fun recents(
        limit: Int,
        since: Long,
    ): List<KeyLast>

    @Query(
        "SELECT component, user_serial, COUNT(*) AS n FROM launch_event WHERE at >= :since " +
            "GROUP BY component, user_serial",
    )
    suspend fun countsSince(since: Long): List<KeyCount>

    @Query(
        "SELECT component, user_serial, at / 86400000 AS day, COUNT(*) AS n FROM launch_event WHERE at >= :since " +
            "GROUP BY component, user_serial, day",
    )
    suspend fun dayCountsSince(since: Long): List<KeyDayCount>

    @Query("SELECT COUNT(*) FROM launch_event")
    suspend fun size(): Int
}
