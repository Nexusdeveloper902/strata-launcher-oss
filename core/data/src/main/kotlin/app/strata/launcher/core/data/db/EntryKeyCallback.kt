package app.strata.launcher.core.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.strata.launcher.core.model.EntryKeyMigration

/**
 * FB-019: moves rows keyed on Strata's old launcher entry to the new one ([EntryKeyMigration]) whenever a database
 * opens. After the first open nothing matches, so later opens cost one indexed lookup per table. Runs in one
 * transaction on the opening thread (I/O, never main). Where a row for the new key already exists it wins and the old
 * one is dropped (`UPDATE OR IGNORE` then `DELETE`); favourites keep their positions, so their order is unchanged.
 */
class EntryKeyCallback(
    private val ownPackage: String,
    private val tables: List<String>,
) : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        migrate(db, ownPackage, tables)
    }

    companion object {
        /** `strata.db`: overrides (hidden, label, icon), favourites and the catalog cache. */
        val STRATA_TABLES = listOf("app_override", "favorite", "app_snapshot")

        /** `launch_log.db`: launch events (usage for search ranking). */
        val LAUNCH_LOG_TABLES = listOf("launch_event")

        fun migrate(
            db: SupportSQLiteDatabase,
            ownPackage: String,
            tables: List<String>,
        ) {
            val target = EntryKeyMigration.currentComponent(ownPackage)
            db.beginTransaction()
            try {
                for (old in EntryKeyMigration.legacyComponents(ownPackage)) {
                    for (table in tables) {
                        db.execSQL(
                            "UPDATE OR IGNORE $table SET component = ? WHERE component = ?",
                            arrayOf(target, old),
                        )
                        db.execSQL("DELETE FROM $table WHERE component = ?", arrayOf(old))
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }
}
