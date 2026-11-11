package app.strata.launcher.core.model

/**
 * Notification badges are counted per package and profile (ANDROID_PLATFORM §8: "map notifications to rows by
 * `(packageName, user)`"), so every launcher activity of the package shows the same count. Memory only
 * (FR-NOTIF-03, NFR-PRIV-04).
 */
data class BadgeKey(
    val packageName: String,
    val userSerial: Long,
)

/** The list with each app's alerting count from [badges] (0 = no badge); unchanged apps keep their instance. */
fun AppList.withBadges(badges: Map<BadgeKey, Int>): AppList {
    fun VisibleApp.badged(): VisibleApp {
        val count = badges[BadgeKey(packageName, key.userSerial)] ?: 0
        return if (count == badge) this else copy(badge = count)
    }
    if (badges.isEmpty() && allApps.none { it.badge != 0 } && privateApps.none { it.badge != 0 }) return this
    return copy(
        sections = sections.map { s -> s.copy(apps = s.apps.map { it.badged() }) },
        hiddenApps = hiddenApps.map { it.badged() },
        privateApps = privateApps.map { it.badged() },
    )
}
