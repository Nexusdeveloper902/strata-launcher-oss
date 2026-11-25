package app.strata.launcher.core.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.EntryKeyMigration

/**
 * FB-019: moves rows keyed on Strata's old launcher entry to the new one ([EntryKeyMigration]) whenever a database
 * opens. After the first successful open nothing matches, so later opens cost one indexed lookup per table. Runs in
 * one transaction on the opening thread (I/O, never main).
 *
 * Rules, per table: where a row for the new key already exists it wins and the old one is dropped (`UPDATE OR IGNORE`,
 * then `DELETE`); favourites keep their positions. For `app_override` an **empty** new-key row (no hidden flag, label or
 * icon, e.g. a reconcile marker) does not win: it is removed first, so the old row's data moves in.
 *
 * **Never fails the open.** [SafeRoomOpener] treats any exception during open as corruption and sets the file aside
 * (the owner would lose favourites, overrides and widgets). A failure here (busy, disk full, read-only) is logged, the
 * transaction rolls back, and the next open tries again: the move is idempotent.
 */
class EntryKeyCallback(
    private val ownPackage: String,
    private val tables: List<String>,
    private val logger: Logger,
    /** Runs one statement; replaced in tests to simulate a failing database. */
    private val exec: (SupportSQLiteDatabase, String, Array<Any?>) -> Unit = { db, sql, args -> db.execSQL(sql, args) },
) : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        try {
            migrate(db)
        } catch (e: Exception) {
            logger.w(TAG, "entry key move failed; retried on the next open", e)
        }
    }

    private fun migrate(db: SupportSQLiteDatabase) {
        val target = EntryKeyMigration.currentComponent(ownPackage)
        db.beginTransaction()
        try {
            for (old in EntryKeyMigration.legacyComponents(ownPackage)) {
                for (table in tables) {
                    if (table == OVERRIDES) {
                        exec(db, DROP_EMPTY_TARGET_OVERRIDES, arrayOf(target, old))
                    }
                    exec(db, "UPDATE OR IGNORE $table SET component = ? WHERE component = ?", arrayOf(target, old))
                    exec(db, "DELETE FROM $table WHERE component = ?", arrayOf(old))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        private const val TAG = "Db"
        private const val OVERRIDES = "app_override"

        /** `strata.db`: overrides (hidden, label, icon), favourites and the catalog cache. */
        val STRATA_TABLES = listOf(OVERRIDES, "favorite", "app_snapshot")

        /** `launch_log.db`: launch events (usage for search ranking). */
        val LAUNCH_LOG_TABLES = listOf("launch_event")

        /** An all-default new-key override that an old-key row exists for (same user) gives way to it. */
        private const val DROP_EMPTY_TARGET_OVERRIDES =
            "DELETE FROM app_override WHERE component = ?1 AND hidden = 0 AND custom_label IS NULL " +
                "AND icon_kind IS NULL AND icon_pack IS NULL AND icon_drawable IS NULL " +
                "AND user_serial IN (SELECT user_serial FROM app_override WHERE component = ?2)"
    }
}
