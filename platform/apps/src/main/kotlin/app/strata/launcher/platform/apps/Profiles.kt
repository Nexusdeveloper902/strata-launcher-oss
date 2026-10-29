package app.strata.launcher.platform.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
 * [ProfileSource] for M1: main and work profiles from `LauncherApps.getProfiles()`, refreshed by the
 * runtime profile broadcasts. Quiet-mode requests and private space arrive in M6.
 */
class AndroidProfileSource(context: Context) : ProfileSource {
    private val app = context.applicationContext
    private val resolver = ProfileResolver(app)
    private val state = MutableStateFlow<List<Profile>>(emptyList())

    override val profiles: StateFlow<List<Profile>> = state

    /** Reads the profiles off main and keeps them current; call once. */
    fun start() {
        PlatformThread.handler.post { refresh() }
        registerProfileReceiver(app) { refresh() }
    }

    private fun refresh() {
        state.value = runCatching { resolver.snapshot() }.getOrDefault(state.value)
    }
}
