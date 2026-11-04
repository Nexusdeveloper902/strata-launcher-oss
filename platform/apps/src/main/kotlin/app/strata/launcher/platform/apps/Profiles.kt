package app.strata.launcher.platform.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.LauncherUserInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.core.content.ContextCompat
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.platformapi.apps.Profile
import app.strata.launcher.core.platformapi.apps.ProfileSource
import app.strata.launcher.core.platformapi.apps.QuietModeResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** The `strata-platform` thread for LauncherApps and profile callbacks (ARCHITECTURE §6). */
object PlatformThread {
    val looper: Looper by lazy { HandlerThread("strata-platform").apply { start() }.looper }
    val handler: Handler by lazy { Handler(looper) }
}

/** Profile helpers shared by the adapters of this module. */
internal class ProfileResolver(context: Context) {
    private val app = context.applicationContext
    val launcherApps: LauncherApps = checkNotNull(app.getSystemService(LauncherApps::class.java))
    val userManager: UserManager = checkNotNull(app.getSystemService(UserManager::class.java))

    fun serialOf(user: UserHandle): Long = userManager.getSerialNumberForUser(user)

    fun userOf(serial: Long): UserHandle? = userManager.getUserForSerialNumber(serial)

    /**
     * MAIN for this user. On API 35+ the launcher user type tells managed, private and other profiles
     * apart; below 35 every other profile a launcher can see is treated as WORK (ANDROID_PLATFORM §3.2).
     */
    fun kindOf(user: UserHandle): ProfileKind {
        if (user == Process.myUserHandle()) return ProfileKind.MAIN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val type = runCatching { launcherApps.getLauncherUserInfo(user)?.userType }.getOrNull()
            return when (type) {
                UserManager.USER_TYPE_PROFILE_MANAGED -> ProfileKind.WORK
                UserManager.USER_TYPE_PROFILE_PRIVATE -> ProfileKind.PRIVATE
                null -> ProfileKind.WORK
                else -> ProfileKind.OTHER
            }
        }
        return ProfileKind.WORK
    }

    fun quiet(user: UserHandle): Boolean =
        user != Process.myUserHandle() && runCatching { userManager.isQuietModeEnabled(user) }.getOrDefault(false)

    fun profiles(): List<UserHandle> =
        runCatching { launcherApps.profiles }.getOrDefault(
            listOf(Process.myUserHandle()),
        )

    fun snapshot(): List<Profile> =
        profiles().map { user ->
            val quiet = quiet(user)
            Profile(serialOf(user), kindOf(user), quietMode = quiet, available = !quiet)
        }
}

/** Runtime-only profile broadcasts (ANDROID_PLATFORM §3.2: never delivered to manifest receivers). */
internal fun profileBroadcastFilter(): IntentFilter =
    IntentFilter().apply {
        addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
        addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
        addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
        addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
        addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            addAction(Intent.ACTION_PROFILE_ADDED)
            addAction(Intent.ACTION_PROFILE_REMOVED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            addAction(Intent.ACTION_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
        }
    }

internal fun registerProfileReceiver(
    context: Context,
    onChange: () -> Unit,
): BroadcastReceiver {
    val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = onChange()
        }
    ContextCompat.registerReceiver(
        context.applicationContext,
        receiver,
        profileBroadcastFilter(),
        null,
        PlatformThread.handler,
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    return receiver
}

/**
 * [ProfileSource]: main, work and private-space profiles from `LauncherApps.getProfiles()` (a private space is listed
 * only while Strata holds ROLE_HOME and `ACCESS_HIDDEN_PROFILES`), refreshed by the runtime profile broadcasts.
 * Quiet-mode requests pause/resume work and lock/unlock the private space (ANDROID_PLATFORM §3.2, §3.3).
 */
class AndroidProfileSource(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ProfileSource {
    private val app = context.applicationContext
    private val resolver = ProfileResolver(app)
    private val state = MutableStateFlow<List<Profile>>(emptyList())

    override val profiles: StateFlow<List<Profile>> = state

    /** Reads the profiles off main and keeps them current; call once. */
    fun start() {
        PlatformThread.handler.post { read() }
        registerProfileReceiver(app) { read() }
    }

    private fun read() {
        state.value = runCatching { resolver.snapshot() }.getOrDefault(state.value)
    }

    override fun refresh() {
        PlatformThread.handler.post { read() }
    }

    override suspend fun requestQuietMode(
        serial: Long,
        enable: Boolean,
    ): QuietModeResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return QuietModeResult.UNSUPPORTED
        return withContext(io) {
            val user = resolver.userOf(serial) ?: return@withContext QuietModeResult.FAILED
            val result =
                try {
                    // "false" = the system shows its credential screen first (ANDROID_PLATFORM §3.2).
                    if (resolver.userManager.requestQuietModeEnabled(enable, user)) {
                        QuietModeResult.DONE
                    } else {
                        QuietModeResult.NEEDS_CREDENTIAL
                    }
                } catch (e: SecurityException) {
                    QuietModeResult.NOT_ALLOWED
                } catch (e: IllegalArgumentException) {
                    QuietModeResult.FAILED
                } catch (e: IllegalStateException) {
                    QuietModeResult.FAILED
                }
            read()
            result
        }
    }

    override fun privateSpaceEntryHidden(serial: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        return try {
            val user = resolver.userOf(serial) ?: return false
            resolver.launcherApps
                .getLauncherUserInfo(user)
                ?.userConfig
                ?.getBoolean(LauncherUserInfo.PRIVATE_SPACE_ENTRYPOINT_HIDDEN, false) == true
        } catch (e: RuntimeException) {
            false
        }
    }
}
