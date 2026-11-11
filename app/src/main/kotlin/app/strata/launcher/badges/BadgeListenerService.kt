package app.strata.launcher.badges

import app.strata.launcher.appGraph
import app.strata.launcher.platform.notifications.BadgeListener

/**
 * The notification listener of ANDROID_PLATFORM §22 (`.badges.BadgeListenerService`). The system binds it only after
 * the user granted access in the system settings (FR-NOTIF-01). It makes sure the process's badge source exists
 * before the first callback (a listener bound at boot can start the process before any activity).
 */
class BadgeListenerService : BadgeListener() {
    override fun onCreate() {
        appGraph.badges
        super.onCreate()
    }
}
