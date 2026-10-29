package app.strata.launcher.catalog

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.strata.launcher.core.data.Databases
import app.strata.launcher.core.data.ResetNoticeBoard
import app.strata.launcher.core.data.db.StrataDatabase
import app.strata.launcher.core.platformapi.settings.ResetCause
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.platform.apps.LauncherAppsCatalogSource
import app.strata.launcher.testing.fakes.RecordingLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * Level I tests of the M1 DoD that need a real device (run on the owner's phone or a managed device;
 * never in this repo's container). Package install/disable cases are in [ListDeviceTest].
 */
@RunWith(AndroidJUnit4::class)
class CatalogDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * T-APPS-01: the catalog equals `getActivityList(null, user)` for every profile (visibility through the
     * manifest `<queries>`, no QUERY_ALL_PACKAGES; U-015). Run on API 30, 33, 35, 36 with `APPS120` installed.
     */
    @Test
    fun tApps01_inventoryEqualsLauncherApps() {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val users = context.getSystemService(UserManager::class.java)
        val source = LauncherAppsCatalogSource(context, Dispatchers.IO, RecordingLogger())
        val load = runBlocking { source.loadAll() }
        val expected =
            launcherApps.profiles
                .filter { users.getSerialNumberForUser(it) in load.loadedSerials }
                .flatMap { user ->
                    val serial = users.getSerialNumberForUser(user)
                    launcherApps.getActivityList(null, user).map { it.componentName.flattenToString() to serial }
                }
        assertThat(load.entries.map { it.key.component to it.key.userSerial }).containsExactlyElementsIn(expected)
        assertThat(load.entries).isNotEmpty()
    }

    /** T-LIFE-11 (device half): `strata.db` truncated to 1 KiB → quarantine, banner, usable DB, no crash. */
    @Test
    fun tLife11_truncatedDatabaseIsRebuilt() {
        val notices = ResetNoticeBoard()
        Databases.openStrata(context, RecordingLogger(), notices).close()
        val file = context.getDatabasePath(StrataDatabase.FILE_NAME)
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        RandomAccessFile(file, "rw").use { it.setLength(1024) }
        val db = Databases.openStrata(context, RecordingLogger(), notices)
        assertThat(runBlocking { db.snapshotDao().all() }).isEmpty()
        db.close()
        assertThat(notices.causes.value).contains(ResetCause.DATABASE_CORRUPT)
        assertThat(File(context.filesDir, "quarantine").listFiles()).isNotEmpty()
    }

    /**
     * T-LIFE-15 (automated part): HomeActivity is not direct-boot aware, so the system never starts it before
     * the first unlock and no DB access can happen while the user is locked. The reboot itself is MAN.
     */
    @Test
    fun tLife15_homeIsNotDirectBootAware() {
        val pm = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        val info = pm.getActivityInfo(ComponentName(context, HomeActivity::class.java), PackageManager.GET_META_DATA)
        assertThat(info.directBootAware).isFalse()
    }
}
