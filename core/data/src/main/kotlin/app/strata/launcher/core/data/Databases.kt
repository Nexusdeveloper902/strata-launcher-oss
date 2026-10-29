package app.strata.launcher.core.data

import android.content.Context
import androidx.room.Room
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.data.db.SafeRoomOpener
import app.strata.launcher.core.data.db.StrataDatabase
import app.strata.launcher.core.data.launchlog.LaunchLogDatabase
import app.strata.launcher.core.platformapi.settings.ResetCause
import app.strata.launcher.core.platformapi.settings.ResetNotices
import java.io.File

/** Opening the two Room databases of ARCHITECTURE §4.1 through [SafeRoomOpener]. Blocking: call on I/O. */
object Databases {
    /** `strata.db` in the default database directory; damaged files go to `filesDir/quarantine/` + banner. */
    fun openStrata(
        context: Context,
        logger: Logger,
        resetNotices: ResetNotices,
    ): StrataDatabase {
        val app = context.applicationContext
        return SafeRoomOpener(
            file = app.getDatabasePath(StrataDatabase.FILE_NAME),
            quarantineDir = File(app.filesDir, "quarantine"),
            logger = logger,
            onReset = { resetNotices.report(ResetCause.DATABASE_CORRUPT) },
        ) { file, factory ->
            Room
                .databaseBuilder(app, StrataDatabase::class.java, file.absolutePath)
                .openHelperFactory(factory)
                .build()
        }.open()
    }

    /** `noBackupFilesDir/launch_log.db`; disposable, so damage deletes it without a banner. */
    fun openLaunchLog(
        context: Context,
        logger: Logger,
    ): LaunchLogDatabase {
        val app = context.applicationContext
        return SafeRoomOpener(
            file = File(app.noBackupFilesDir, LaunchLogDatabase.FILE_NAME),
            quarantineDir = null,
            logger = logger,
            onReset = {},
        ) { file, factory ->
            Room
                .databaseBuilder(app, LaunchLogDatabase::class.java, file.absolutePath)
                .openHelperFactory(factory)
                .build()
        }.open()
    }
}
