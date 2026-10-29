package app.strata.launcher.platform.apps

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.window.SplashScreen
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.AppKey
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
    private val resolver by lazy { ProfileResolver(context.applicationContext) }

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

    private inline fun start(
        key: AppKey,
        what: String,
        block: (ComponentName, UserHandle) -> Unit,
    ): LaunchResult {
        val component = ComponentName.unflattenFromString(key.component) ?: return Outcome.Err(LaunchError.NOT_FOUND)
        return try {
            val user = resolver.userOf(key.userSerial) ?: return Outcome.Err(LaunchError.NOT_FOUND)
            if (resolver.quiet(user)) return Outcome.Err(LaunchError.PROFILE_PAUSED)
            block(component, user)
            Outcome.Ok(Unit)
        } catch (e: ActivityNotFoundException) {
            fail(what, LaunchError.NOT_FOUND, e)
        } catch (e: SecurityException) {
            fail(what, LaunchError.SECURITY, e)
        } catch (e: IllegalStateException) {
            fail(what, LaunchError.UNKNOWN, e)
        } catch (e: IllegalArgumentException) {
            fail(what, LaunchError.NOT_FOUND, e)
        }
    }

    private fun fail(
        what: String,
        error: LaunchError,
        e: Exception,
    ): LaunchResult {
        logger.w(TAG, "$what failed: $error", e)
        return Outcome.Err(error)
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
