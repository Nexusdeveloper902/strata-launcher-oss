package app.strata.launcher.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * `strata.db` version 1 (ARCHITECTURE §4.1). Every schema change ships a `Migration` plus a test
 * (T-LIFE-14). There is no destructive fallback: an open or migration failure takes the FR-LIFE-04
 * rebuild path in [SafeRoomOpener].
 */
@Database(
    entities = [
        AppSnapshotEntity::class,
        AppOverrideEntity::class,
        FavoriteEntity::class,
        WidgetPlacementEntity::class,
    ],
    version = StrataDatabase.VERSION,
    exportSchema = true,
)
abstract class StrataDatabase : RoomDatabase() {
    abstract fun snapshotDao(): AppSnapshotDao

    abstract fun overrideDao(): AppOverrideDao

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "strata.db"
    }
}

/** Catalog cache (never wins over the platform). PRIVATE rows are never written (P-5). */
@Entity(tableName = "app_snapshot", primaryKeys = ["component", "user_serial"])
data class AppSnapshotEntity(
    val component: String,
    @ColumnInfo(name = "user_serial") val userSerial: Long,
    @ColumnInfo(name = "system_label") val systemLabel: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    /** [ProfileKindCodes]: MAIN, WORK or OTHER. */
    @ColumnInfo(name = "profile_kind") val profileKind: Int,
    val flags: Int,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "last_update_time") val lastUpdateTime: Long,
    /** Collation bucket at write time. */
    @ColumnInfo(name = "section_key") val sectionKey: String,
)

@Entity(tableName = "app_override", primaryKeys = ["component", "user_serial"])
data class AppOverrideEntity(
    val component: String,
    @ColumnInfo(name = "user_serial") val userSerial: Long,
    val hidden: Boolean = false,
    @ColumnInfo(name = "custom_label") val customLabel: String? = null,
    @ColumnInfo(name = "icon_kind") val iconKind: String? = null,
    @ColumnInfo(name = "icon_pack") val iconPack: String? = null,
    @ColumnInfo(name = "icon_drawable") val iconDrawable: String? = null,
    @ColumnInfo(name = "missing_since") val missingSince: Long? = null,
)

/** Favourites order (FR-FAV-01; written from M3). */
@Entity(
    tableName = "favorite",
    indices = [Index(value = ["component", "user_serial"], unique = true)],
)
data class FavoriteEntity(
    @PrimaryKey val position: Int,
    val component: String,
    @ColumnInfo(name = "user_serial") val userSerial: Long,
)

/** Widget placement (FR-WIDGET-*; written from M7). */
@Entity(tableName = "widget_placement")
data class WidgetPlacementEntity(
    @PrimaryKey @ColumnInfo(name = "app_widget_id") val appWidgetId: Int,
    val provider: String,
    @ColumnInfo(name = "user_serial") val userSerial: Long,
    @ColumnInfo(name = "height_step") val heightStep: Int,
    @ColumnInfo(name = "replace_clock") val replaceClock: Boolean,
    val state: Int,
)

@Dao
interface AppSnapshotDao {
    @Query("SELECT * FROM app_snapshot")
    suspend fun all(): List<AppSnapshotEntity>

    @Upsert
    suspend fun upsert(rows: List<AppSnapshotEntity>)

    @Query("DELETE FROM app_snapshot WHERE component = :component AND user_serial = :userSerial")
    suspend fun delete(
        component: String,
        userSerial: Long,
    )

    @Transaction
    suspend fun apply(
        upserts: List<AppSnapshotEntity>,
        deletes: List<Pair<String, Long>>,
    ) {
        deletes.forEach { (component, serial) -> delete(component, serial) }
        if (upserts.isNotEmpty()) upsert(upserts)
    }
}

@Dao
interface AppOverrideDao {
    @Query("SELECT * FROM app_override")
    fun observeAll(): Flow<List<AppOverrideEntity>>

    @Query("SELECT * FROM app_override")
    suspend fun all(): List<AppOverrideEntity>

    @Upsert
    suspend fun upsert(row: AppOverrideEntity)

    @Query(
        "UPDATE app_override SET missing_since = :now " +
            "WHERE component = :component AND user_serial = :userSerial AND missing_since IS NULL",
    )
    suspend fun markMissing(
        component: String,
        userSerial: Long,
        now: Long,
    )

    @Query("UPDATE app_override SET missing_since = NULL WHERE component = :component AND user_serial = :userSerial")
    suspend fun clearMissing(
        component: String,
        userSerial: Long,
    )

    @Query("DELETE FROM app_override WHERE missing_since IS NOT NULL AND missing_since < :cutoff")
    suspend fun purgeMissingBefore(cutoff: Long): Int

    /** FR-LIFE-03: removed apps keep their records 30 days ([markMissing]); reinstalled apps get them back. */
    @Transaction
    suspend fun reconcile(
        present: List<Pair<String, Long>>,
        missing: List<Pair<String, Long>>,
        now: Long,
        purgeCutoff: Long,
    ) {
        present.forEach { (c, s) -> clearMissing(c, s) }
        missing.forEach { (c, s) -> markMissing(c, s, now) }
        purgeMissingBefore(purgeCutoff)
    }
}
