package app.strata.launcher.platform.apps

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.window.SplashScreen
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.platformapi.apps.AppShortcut
import app.strata.launcher.core.platformapi.apps.LaunchError
import app.strata.launcher.core.platformapi.apps.LaunchOrigin
import app.strata.launcher.core.platformapi.apps.LaunchResult
import app.strata.launcher.core.platformapi.apps.ShortcutSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * [ShortcutSource] over `LauncherApps` (FR-APPS-08, ANDROID_PLATFORM §6). Only the default launcher holds the
 * shortcut-host permission; without it every query is empty and the sheet shows no shortcut section. Nothing is
 * stored: pinned shortcuts are v1.x, so there are no pins to keep.
 */
class AndroidShortcutSource(
    context: Context,
    private val io: CoroutineDispatcher,
    private val logger: Logger,
) : ShortcutSource {
    private val appContext = context.applicationContext
    private val resolver by lazy { ProfileResolver(appContext) }

    override fun hasHostPermission(): Boolean =
        try {
            resolver.launcherApps.hasShortcutHostPermission()
        } catch (e: RuntimeException) {
            // SecurityException / IllegalStateException while the user is locked: no shortcuts for now.
            false
        }

    override suspend fun shortcutsFor(key: AppKey): List<AppShortcut> =
        withContext(io) {
            val component = ComponentName.unflattenFromString(key.component) ?: return@withContext emptyList()
            val user = resolver.userOf(key.userSerial) ?: return@withContext emptyList()
            if (!hasHostPermission() || resolver.quiet(user)) return@withContext emptyList()
            val query =
                LauncherApps
                    .ShortcutQuery()
                    .setPackage(component.packageName)
                    .setActivity(component)
                    .setQueryFlags(
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC,
                    )
            val found: List<ShortcutInfo> =
                try {
                    resolver.launcherApps.getShortcuts(query, user).orEmpty()
                } catch (e: SecurityException) {
                    logger.w(TAG, "no shortcut host permission", e)
                    emptyList()
                } catch (e: IllegalStateException) {
                    // User locked (direct boot): defer (ANDROID_PLATFORM §6).
                    emptyList()
                }
            order(found.filter { it.isEnabled }.map { it.toModel(key.userSerial) })
        }

    override fun launch(
        shortcut: AppShortcut,
        origin: LaunchOrigin?,
    ): LaunchResult {
        val user = resolver.userOf(shortcut.userSerial) ?: return Outcome.Err(LaunchError.NOT_FOUND)
        return try {
            if (resolver.quiet(user)) return Outcome.Err(LaunchError.PROFILE_PAUSED)
            resolver.launcherApps.startShortcut(
                shortcut.packageName,
                shortcut.id,
                origin?.let { Rect(it.left, it.top, it.right, it.bottom) },
                options(),
                user,
            )
            Outcome.Ok(Unit)
        } catch (e: ActivityNotFoundException) {
            Outcome.Err(LaunchError.NOT_FOUND)
        } catch (e: IllegalStateException) {
            // The shortcut was removed or disabled meanwhile.
            Outcome.Err(LaunchError.NOT_FOUND)
        } catch (e: IllegalArgumentException) {
            Outcome.Err(LaunchError.NOT_FOUND)
        } catch (e: SecurityException) {
            Outcome.Err(LaunchError.SECURITY)
        }
    }

    private fun ShortcutInfo.toModel(serial: Long) =
        AppShortcut(
            id = id,
            packageName = `package`,
            userSerial = serial,
            label = (shortLabel ?: longLabel)?.toString().orEmpty(),
            dynamic = isDynamic,
            rank = rank,
        )

    private fun options(): Bundle? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityOptions.makeBasic().setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_ICON).toBundle()
        } else {
            null
        }

    companion object {
        private const val TAG = "Shortcuts"

        /** Rows shown in the sheet (Proposed: the usual launcher maximum). */
        const val MAX_SHORTCUTS = 5

        /** Manifest shortcuts first, then dynamic ones, each by rank; at most [MAX_SHORTCUTS]; blank labels dropped. */
        fun order(shortcuts: List<AppShortcut>): List<AppShortcut> =
            shortcuts
                .filter { it.label.isNotBlank() }
                .sortedWith(compareBy<AppShortcut> { it.dynamic }.thenBy { it.rank })
                .take(MAX_SHORTCUTS)
    }
}
