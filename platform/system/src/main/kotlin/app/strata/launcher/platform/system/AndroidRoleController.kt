package app.strata.launcher.platform.system

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import app.strata.launcher.core.platformapi.system.RoleController
import app.strata.launcher.core.platformapi.system.SystemIntent

/**
 * [RoleController] on the framework (ANDROID_PLATFORM §1 "Failure", §2):
 * API 29+ uses `RoleManager` for ROLE_HOME; API 26–28 resolves `MAIN/HOME` and compares the package.
 */
class AndroidRoleController(context: Context) : RoleController {
    private val appContext = context.applicationContext

    override fun isDefaultHome(): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roles = appContext.getSystemService(RoleManager::class.java)
                roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && roles.isRoleHeld(RoleManager.ROLE_HOME)
            } else {
                preferredHomePackage() == appContext.packageName
            }
        } catch (e: RuntimeException) {
            // SecurityException / IllegalStateException while the user is locked: treat as "not default".
            false
        }

    override fun requestIntent(): SystemIntent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && roleAvailable()) {
            SystemIntent.RequestHomeRole
        } else {
            SystemIntent.HomeSettings
        }

    override fun fallbackIntents(): List<SystemIntent> = FALLBACK_CHAIN

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun roleAvailable(): Boolean =
        try {
            appContext.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_HOME) == true
        } catch (e: RuntimeException) {
            false
        }

    private fun preferredHomePackage(): String? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return appContext.packageManager
            .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
    }

    companion object {
        /** ANDROID_PLATFORM §2: home settings → default apps → settings. */
        val FALLBACK_CHAIN: List<SystemIntent> =
            listOf(
                SystemIntent.HomeSettings,
                SystemIntent.DefaultAppsSettings,
                SystemIntent.Settings,
            )
    }
}

/** Builds the framework [Intent] for a [SystemIntent]. Returns null when it cannot exist on this API level. */
object SystemIntentFactory {
    fun create(context: Context, intent: SystemIntent): Intent? =
        when (intent) {
            SystemIntent.RequestHomeRole -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.getSystemService(RoleManager::class.java)?.createRequestRoleIntent(RoleManager.ROLE_HOME)
                } else {
                    null
                }
            }

            SystemIntent.HomeSettings -> {
                Intent(Settings.ACTION_HOME_SETTINGS)
            }

            SystemIntent.DefaultAppsSettings -> {
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            }

            SystemIntent.Settings -> {
                Intent(Settings.ACTION_SETTINGS)
            }
        }
}
