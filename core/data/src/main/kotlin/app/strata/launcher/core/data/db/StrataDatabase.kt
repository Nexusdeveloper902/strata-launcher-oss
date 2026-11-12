package app.strata.launcher.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
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

    abstract fun favoriteDao(): FavoriteDao

    abstract fun widgetPlacementDao(): WidgetPlacementDao

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

    @Query("SELECT * FROM app_override WHERE component = :component AND user_serial = :userSerial")
    suspend fun get(
        component: String,
        userSerial: Long,
    ): AppOverrideEntity?

    @Query("DELETE FROM app_override WHERE component = :component AND user_serial = :userSerial")
    suspend fun delete(
        component: String,
        userSerial: Long,
    )

    /** Read-modify-write of one row; a row left with only defaults is deleted (FR-APPS-04/05 writes). */
    @Transaction
    suspend fun edit(
        component: String,
        userSerial: Long,
        transform: (AppOverrideEntity) -> AppOverrideEntity,
    ) {
        val current = get(component, userSerial) ?: AppOverrideEntity(component, userSerial)
        val next = transform(current)
        val empty =
            !next.hidden && next.customLabel == null && next.iconKind == null &&
                next.iconPack == null && next.iconDrawable == null
        if (empty) delete(component, userSerial) else upsert(next)
    }

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

    /** Favourites whose record expires with [purgeMissingBefore] go with it (FR-LIFE-03, FR-BACKUP-03). */
    @Query(
        "DELETE FROM favorite WHERE EXISTS (SELECT 1 FROM app_override o WHERE o.component = favorite.component " +
            "AND o.user_serial = favorite.user_serial AND o.missing_since IS NOT NULL AND o.missing_since < :cutoff)",
    )
    suspend fun purgeFavoritesMissingBefore(cutoff: Long): Int

    /** A row that only marked a pending favourite (all defaults) is dropped once the app is back. */
    @Query(
        "DELETE FROM app_override WHERE component = :component AND user_serial = :userSerial AND hidden = 0 " +
            "AND custom_label IS NULL AND icon_kind IS NULL AND icon_pack IS NULL AND icon_drawable IS NULL",
    )
    suspend fun deleteIfEmpty(
        component: String,
        userSerial: Long,
    )

    /**
     * FR-LIFE-03: removed apps keep their records 30 days ([markMissing]); reinstalled apps get them back. Expired records
     * (and their favourites) are purged **first**, so an app that comes back after 31 days is not given them (T-BACKUP-08).
     * [markers] are favourites of missing apps without a record: they get an all-default row carrying `missing_since`.
     */
    @Transaction
    suspend fun reconcile(
        present: List<Pair<String, Long>>,
        missing: List<Pair<String, Long>>,
        now: Long,
        purgeCutoff: Long,
        markers: List<Pair<String, Long>> = emptyList(),
    ) {
        purgeFavoritesMissingBefore(purgeCutoff)
        purgeMissingBefore(purgeCutoff)
        present.forEach { (c, s) ->
            clearMissing(c, s)
            deleteIfEmpty(c, s)
        }
        missing.forEach { (c, s) -> markMissing(c, s, now) }
        markers.forEach { (c, s) -> if (get(c, s) == null) upsert(AppOverrideEntity(c, s, missingSince = now)) }
    }

    @Query("DELETE FROM app_override")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(rows: List<AppOverrideEntity>)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorite ORDER BY position")
    fun observe(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorite ORDER BY position")
    suspend fun all(): List<FavoriteEntity>

    @Query("DELETE FROM favorite")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(rows: List<FavoriteEntity>)

    /** The whole order in one transaction (ARCHITECTURE §4: reorder = one transaction). */
    @Transaction
    suspend fun replaceAll(keys: List<Pair<String, Long>>) {
        deleteAll()
        insertAll(keys.mapIndexed { i, (component, serial) -> FavoriteEntity(i, component, serial) })
    }

    /** Read-modify-write of the whole order in one transaction; [transform] returns null for no change. */
    @Transaction
    suspend fun update(transform: (List<Pair<String, Long>>) -> List<Pair<String, Long>>?) {
        val next = transform(all().map { it.component to it.userSerial }) ?: return
        replaceAll(next)
    }
}

/** The widget area's placement (FR-WIDGET-01…04). v1 keeps at most one row. */
@Dao
interface WidgetPlacementDao {
    @Query("SELECT * FROM widget_placement ORDER BY app_widget_id LIMIT 1")
    fun observe(): Flow<WidgetPlacementEntity?>

    @Query("SELECT * FROM widget_placement")
    suspend fun all(): List<WidgetPlacementEntity>

    @Query("DELETE FROM widget_placement")
    suspend fun deleteAll()

    @Upsert
    suspend fun upsert(row: WidgetPlacementEntity)

    @Insert
    suspend fun insertAll(rows: List<WidgetPlacementEntity>)

    @Query("DELETE FROM widget_placement WHERE app_widget_id = :id")
    suspend fun delete(id: Int)

    @Query("UPDATE widget_placement SET height_step = :step WHERE app_widget_id = :id")
    suspend fun setHeightStep(
        id: Int,
        step: Int,
    )

    @Query("UPDATE widget_placement SET replace_clock = :replaceClock WHERE app_widget_id = :id")
    suspend fun setReplaceClock(
        id: Int,
        replaceClock: Boolean,
    )

    @Query("UPDATE widget_placement SET state = :state WHERE app_widget_id = :id")
    suspend fun setState(
        id: Int,
        state: Int,
    )

    /** The new row is the only one; returns the IDs it replaced. */
    @Transaction
    suspend fun replaceAll(row: WidgetPlacementEntity): List<Int> {
        val old = all().map { it.appWidgetId }.filter { it != row.appWidgetId }
        deleteAll()
        upsert(row)
        return old
    }

    /** `APPWIDGET_HOST_RESTORED`: rows move to their new IDs in one transaction ([newState] per row). */
    @Transaction
    suspend fun remap(
        map: Map<Int, Int?>,
        bound: Int,
        unavailable: Int,
    ) {
        val rows = all()
        val moved =
            rows.filter { it.appWidgetId in map }.map { row ->
                val target = map[row.appWidgetId]
                if (target != null && target > 0) {
                    row.copy(appWidgetId = target, state = bound)
                } else {
                    row.copy(state = unavailable)
                }
            }
        if (moved.isEmpty()) return
        rows.filter { it.appWidgetId in map }.forEach { delete(it.appWidgetId) }
        moved.forEach { upsert(it) }
    }
}
