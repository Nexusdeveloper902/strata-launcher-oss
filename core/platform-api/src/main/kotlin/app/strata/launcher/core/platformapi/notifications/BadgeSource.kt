package app.strata.launcher.core.platformapi.notifications

import app.strata.launcher.core.model.BadgeKey
import app.strata.launcher.core.platformapi.apps.LaunchResult
import kotlinx.coroutines.flow.StateFlow

/** Notification-listener access (ARCHITECTURE §3 `ListenerAccess`, ANDROID_PLATFORM §8). */
enum class ListenerAccess {
    /** Low-RAM device on API ≤ 29: listeners can never be granted or bound there; the feature is hidden (FR-NOTIF-03). */
    UNSUPPORTED,

    /** The user has not granted access, or revoked it. */
    NOT_GRANTED,

    /** Granted in the system settings; the listener is not connected (yet). */
    GRANTED,

    /** Granted and connected: [BadgeSource.badges] is live. */
    CONNECTED,
    ;

    val granted: Boolean get() = this == GRANTED || this == CONNECTED
}

/**
 * Notification badges (FR-NOTIF-01…03), implemented in `:platform:notifications` over a `NotificationListenerService`.
 * Only counts are kept, in memory: no title, text, extras or other content is read, stored, logged or backed up
 * (NFR-PRIV-04). Ongoing and silent notifications and group summaries are not counted (FR-NOTIF-02).
 */
interface BadgeSource {
    val access: StateFlow<ListenerAccess>

    /** Alerting count per (package, profile); empty unless connected and [setActive] is on. */
    val badges: StateFlow<Map<BadgeKey, Int>>

    /**
     * Settings field 13 `badges_enabled`. Off: the counts are dropped and the listener asks to be unbound, so nothing is
     * received. On: a granted but disconnected listener asks to be rebound (U-020).
     */
    fun setActive(active: Boolean)

    /** Re-reads [access] (activity `onResume`) and returns it; rebinds when active, granted and disconnected. Main-safe. */
    suspend fun refresh(): ListenerAccess

    /**
     * Opens the system grant page for our listener (`ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` on API 30+, else the
     * listener list). Only after the in-app disclosure (J-09).
     */
    fun openGrantScreen(): LaunchResult
}
