package app.strata.launcher.core.platformapi.system

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Minute ticks for the home header (ARCHITECTURE §3 `Clock.ticks`; VISUAL_SPEC §9.4): emits once on collection
 * and on every `ACTION_TIME_TICK`, `ACTION_TIME_CHANGED` and `ACTION_TIMEZONE_CHANGED` while collected.
 */
interface TimeTicks {
    val ticks: Flow<Unit>
}

/**
 * The opt-in lock-only accessibility service (ARCHITECTURE §3 `LockService`, ANDROID_PLATFORM §10, FR-GEST-03).
 * [supported] is false below API 28 (the feature is hidden there).
 */
interface LockService {
    val supported: Boolean

    /** True while the user has the service turned on in the system accessibility settings. */
    val enabled: StateFlow<Boolean>

    /** Re-reads [enabled] (activity `onResume`). */
    fun refresh()

    /** `GLOBAL_ACTION_LOCK_SCREEN`; false when the service is not connected. */
    fun lock(): Boolean
}
