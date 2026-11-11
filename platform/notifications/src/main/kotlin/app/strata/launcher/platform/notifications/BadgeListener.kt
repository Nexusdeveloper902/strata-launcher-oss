package app.strata.launcher.platform.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * The notification listener (ANDROID_PLATFORM §8, §22). `:app` declares a subclass at `.badges.BadgeListenerService`,
 * guarded by `BIND_NOTIFICATION_LISTENER_SERVICE` and filtered to `conversations|alerting` on API 31+.
 *
 * Callbacks arrive on the main thread; each one reads a few flags and the ranking of one key, hands them to
 * [AndroidBadgeSource] (a queue, O(1)) and returns (ARCHITECTURE §6). Notification content is never read.
 */
open class BadgeListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        AndroidBadgeSource.instance?.onConnected(this)
    }

    override fun onListenerDisconnected() {
        AndroidBadgeSource.instance?.onDisconnected(this)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
    ) {
        sbn ?: return
        AndroidBadgeSource.instance?.onPosted(rawOf(sbn, rankingMap))
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
    ) {
        sbn ?: return
        AndroidBadgeSource.instance?.onRemoved(sbn.key)
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        rankingMap ?: return
        AndroidBadgeSource.instance?.onRanking(rankingsOf(rankingMap))
    }

    override fun onDestroy() {
        AndroidBadgeSource.instance?.onDisconnected(this)
        super.onDestroy()
    }

    internal companion object {
        /** The flags and ranking of [sbn]; the profile is resolved to its serial number later, off main. */
        fun rawOf(
            sbn: StatusBarNotification,
            rankingMap: RankingMap?,
        ): RawNotification {
            val flags = sbn.notification?.flags ?: 0
            val ranking = Ranking()
            val ranked = rankingMap?.getRanking(sbn.key, ranking) == true
            return RawNotification(
                key = sbn.key,
                packageName = sbn.packageName,
                user = sbn.user,
                ongoing =
                    sbn.isOngoing ||
                        flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0,
                groupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0,
                importance = if (ranked) ranking.importance else IMPORTANCE_UNKNOWN,
                canShowBadge = !ranked || ranking.canShowBadge(),
            )
        }

        fun rankingsOf(rankingMap: RankingMap): Map<String, Pair<Int, Boolean>> {
            val ranking = Ranking()
            val keys = rankingMap.orderedKeys ?: return emptyMap()
            val out = HashMap<String, Pair<Int, Boolean>>(keys.size)
            for (key in keys) {
                if (rankingMap.getRanking(key, ranking)) out[key] = ranking.importance to ranking.canShowBadge()
            }
            return out
        }
    }
}

/** [NotificationFacts] before the profile is mapped to its serial number. */
internal data class RawNotification(
    val key: String,
    val packageName: String,
    val user: android.os.UserHandle,
    val ongoing: Boolean,
    val groupSummary: Boolean,
    val importance: Int,
    val canShowBadge: Boolean,
)
