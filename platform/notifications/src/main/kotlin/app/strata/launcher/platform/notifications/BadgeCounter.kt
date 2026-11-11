package app.strata.launcher.platform.notifications

import app.strata.launcher.core.model.BadgeKey

/**
 * What the badge count needs to know about one active notification, and nothing more: no title, text, extras,
 * actions or people are read (FR-NOTIF-03, NFR-PRIV-04). [key] is the platform's `StatusBarNotification.key`, used
 * only to match removals and ranking updates. Memory only.
 */
data class NotificationFacts(
    val key: String,
    val badgeKey: BadgeKey,
    /** `FLAG_ONGOING_EVENT` or `FLAG_FOREGROUND_SERVICE`. */
    val ongoing: Boolean,
    /** `FLAG_GROUP_SUMMARY`: its children are counted instead. */
    val groupSummary: Boolean,
    /** `Ranking.getImportance()`, or [IMPORTANCE_UNKNOWN] when the ranking did not include the key. */
    val importance: Int,
    /** `Ranking.canShowBadge()`: the user turned badges off for the channel or app when false. */
    val canShowBadge: Boolean,
)

/** `NotificationManager.IMPORTANCE_UNSPECIFIED`: no ranking was available; counted as alerting. */
const val IMPORTANCE_UNKNOWN = -1000

/** `NotificationManager.IMPORTANCE_DEFAULT`: the lowest importance that makes a sound (alerting). */
private const val IMPORTANCE_DEFAULT = 3

/** The FR-NOTIF-02 default filter: alerting notifications only (not ongoing, not silent, not a group summary). */
object BadgeCounter {
    fun counts(facts: Collection<NotificationFacts>): Map<BadgeKey, Int> {
        val out = HashMap<BadgeKey, Int>()
        for (f in facts) {
            if (counted(f)) out[f.badgeKey] = (out[f.badgeKey] ?: 0) + 1
        }
        return out
    }

    fun counted(f: NotificationFacts): Boolean =
        !f.ongoing && !f.groupSummary && f.canShowBadge &&
            (f.importance == IMPORTANCE_UNKNOWN || f.importance >= IMPORTANCE_DEFAULT)
}
