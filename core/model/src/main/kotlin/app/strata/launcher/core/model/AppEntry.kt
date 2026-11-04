package app.strata.launcher.core.model

/**
 * Bit set stored in `app_snapshot.flags` (ARCHITECTURE §4.1): system, updated-system, suspended,
 * archived, synthesized-details.
 */
object AppFlags {
    const val SYSTEM: Int = 1
    const val UPDATED_SYSTEM: Int = 1 shl 1
    const val SUSPENDED: Int = 1 shl 2
    const val ARCHIVED: Int = 1 shl 3

    /** Platform-synthesized "app details" entry for an app without a launcher activity (ANDROID_PLATFORM §3.1). */
    const val SYNTHESIZED: Int = 1 shl 4
}

/**
 * One launchable activity in one profile, as the catalog knows it (snapshot row or platform record).
 * The catalog never stores Strata data such as custom labels; those are overrides.
 */
data class AppEntry(
    val key: AppKey,
    val packageName: String,
    /** Label reported by the platform. */
    val systemLabel: String,
    val profileKind: ProfileKind,
    /** [AppFlags] bit set. */
    val flags: Int = 0,
    /** Epoch ms when Strata first saw this key (drives the v1.x "new" marker). */
    val firstSeenAt: Long = 0L,
    /** Package `lastUpdateTime` (icon cache key). */
    val lastUpdateTime: Long = 0L,
) {
    /** Activity class name without the package prefix, e.g. `.camera.VideoActivity` → `VideoActivity`. */
    val activityShortName: String
        get() = key.component.substringAfter('/').substringAfterLast('.')

    fun hasFlag(flag: Int): Boolean = flags and flag != 0
}

/** The current app catalog. [fromSnapshot] is true until the first reconcile with the platform finished. */
data class Catalog(
    val entries: List<AppEntry>,
    val fromSnapshot: Boolean,
) {
    companion object {
        val EMPTY = Catalog(emptyList(), fromSnapshot = true)
    }
}

/** User overrides for one key (ARCHITECTURE §4, `app_override`). Written from M3 on; read in M1. */
data class AppOverride(
    val key: AppKey,
    val hidden: Boolean = false,
    val customLabel: String? = null,
    /** FR-APPS-06 per-app icon, or null for the theme's icon. */
    val icon: IconOverride? = null,
    /** Epoch ms since the app disappeared, or null while it is installed (FR-LIFE-03). */
    val missingSince: Long? = null,
)
