package app.strata.launcher.platform.system

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread

/**
 * FR-SET-08: how Strata was installed, for the Android 13+ "Allow restricted settings" guide. Android 13 blocks
 * notification-listener access and accessibility services for apps installed from a file until the user allows
 * restricted settings in App info; an app-store install is not blocked.
 */
object InstallSource {
    /**
     * True when an app store installed Strata (`InstallSourceInfo.getPackageSource()` is `PACKAGE_SOURCE_STORE`, API 33+).
     * False for a file, an unknown source, below API 33 and on any failure: the guide is then shown, which costs a
     * paragraph at most. A binder call: run it off the main thread.
     */
    @WorkerThread
    fun installedFromStore(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            packageSource(context) == PackageInstaller.PACKAGE_SOURCE_STORE
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun packageSource(context: Context): Int =
        context.packageManager.getInstallSourceInfo(context.packageName).packageSource
}
