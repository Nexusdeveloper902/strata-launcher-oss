package app.strata.launcher.platform.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import app.strata.launcher.core.platformapi.system.LockService
import app.strata.launcher.core.platformapi.system.TimeTicks
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * [TimeTicks] from runtime-registered `ACTION_TIME_TICK` / `TIME_SET` / `TIMEZONE_CHANGED` broadcasts (these are
 * not delivered to manifest receivers). Registered only while collected, so the header ticks only while visible.
 */
class AndroidTimeTicks(context: Context) : TimeTicks {
    private val appContext = context.applicationContext

    override val ticks: Flow<Unit> =
        callbackFlow {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        trySend(Unit)
                    }
                }
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_TICK)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                }
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            trySend(Unit)
            awaitClose { runCatching { appContext.unregisterReceiver(receiver) } }
        }.conflate()
}

/**
 * The lock-only accessibility service (ANDROID_PLATFORM §10, FR-GEST-03). Its XML config requests no event
 * types and no window content, so it can do nothing but [performGlobalAction]. It registers itself with
 * [AndroidLockService] while connected.
 */
class LockAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
        AndroidLockService.instance?.refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (connected === this) connected = null
        AndroidLockService.instance?.refresh()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    internal companion object {
        @Volatile var connected: LockAccessibilityService? = null
    }
}

/**
 * [LockService] over [LockAccessibilityService]. Enablement is read from `AccessibilityManager` (ANDROID_PLATFORM
 * §10); locking needs a connected service instance and API 28+.
 */
class AndroidLockService(context: Context) : LockService {
    private val appContext = context.applicationContext
    private val state = MutableStateFlow(false)

    override val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    override val enabled: StateFlow<Boolean> = state

    // After `supported`: refresh() reads it (property initialisers run in declaration order).
    init {
        instance = this
        refresh()
    }

    override fun refresh() {
        state.value = supported && (LockAccessibilityService.connected != null || enabledInSettings())
    }

    override fun lock(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val service = LockAccessibilityService.connected ?: return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }

    private fun enabledInSettings(): Boolean {
        val manager = appContext.getSystemService(AccessibilityManager::class.java) ?: return false
        val ours = ComponentName(appContext, LockAccessibilityService::class.java)
        return runCatching {
            manager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { ComponentName.unflattenFromString(it.id) == ours }
        }.getOrDefault(false)
    }

    internal companion object {
        @Volatile var instance: AndroidLockService? = null
    }
}
