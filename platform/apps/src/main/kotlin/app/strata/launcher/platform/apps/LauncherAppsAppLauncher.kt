package app.strata.launcher.platform.apps

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.window.SplashScreen
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.platformapi.apps.AppLauncher
import app.strata.launcher.core.platformapi.apps.LaunchError
import app.strata.launcher.core.platformapi.apps.LaunchOrigin
import app.strata.launcher.core.platformapi.apps.LaunchResult

/**
 * [AppLauncher] over `LauncherApps.startMainActivity` with the tapped row's screen bounds
 * (ANDROID_PLATFORM §4, FR-APPS-02). Every platform exception maps to a [LaunchError].
 */
class LauncherAppsAppLauncher(
    context: Context,
    private val logger: Logger,
) : AppLauncher {
    private val appContext = context.applicationContext
    private val resolver by lazy { ProfileResolver(appContext) }

    override fun launch(
        key: AppKey,
        origin: LaunchOrigin?,
    ): LaunchResult =
        start(key, "launch") { component, user ->
            resolver.launcherApps.startMainActivity(component, user, origin?.toRect(), options())
        }

    override fun openAppInfo(
        key: AppKey,
        origin: LaunchOrigin?,
    ): LaunchResult =
        start(key, "app info") { component, user ->
            resolver.launcherApps.startAppDetailsActivity(component, user, origin?.toRect(), null)
        }

    /**
     * FR-APPS-07, ANDROID_PLATFORM §5: `ACTION_DELETE` with the `package:` URI, for the key's user
     * (`EXTRA_USER`). The system shows its own confirmation; the result arrives as `onPackageRemoved`.
     */
    override fun requestUninstall(key: AppKey): LaunchResult =
        start(key, "uninstall") { component, user ->
            val intent =
                Intent(Intent.ACTION_DELETE, Uri.fromParts("package", component.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            if (user != Process.myUserHandle()) intent.putExtra(Intent.EXTRA_USER, user)
            appContext.startActivity(intent)
        }

    private inline fun start(
        key: AppKey,
        what: String,
        block: (ComponentName, UserHandle) -> Unit,
    ): LaunchResult {
        val component = ComponentName.unflattenFromString(key.component) ?: return Outcome.Err(LaunchError.NOT_FOUND)
        var private = false
        return guardLaunch(logger, TAG, what, { private }) {
            val user = resolver.userOf(key.userSerial) ?: return@guardLaunch Outcome.Err(LaunchError.NOT_FOUND)
            // An unreadable type counts as private: nothing about it is logged.
            private = resolver.kindOf(user).let { it == null || it == ProfileKind.PRIVATE }
            if (resolver.quiet(user)) return@guardLaunch Outcome.Err(LaunchError.PROFILE_PAUSED)
            block(component, user)
            Outcome.Ok(Unit)
        }
    }

    /** Keep the system icon splash (API 33+, ANDROID_PLATFORM §4). */
    private fun options(): Bundle? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityOptions.makeBasic().setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_ICON).toBundle()
        } else {
            null
        }

    private fun LaunchOrigin.toRect() = Rect(left, top, right, bottom)

    private companion object {
        const val TAG = "Launch"
    }
}
