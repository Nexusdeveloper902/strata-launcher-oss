package app.strata.launcher.core.data.catalog

import app.strata.launcher.core.model.AppEntry
import app.strata.launcher.core.model.AppKey

/** Outcome of one reconcile pass: the new entry list and the snapshot diff to persist. */
data class ReconcileResult(
    val entries: List<AppEntry>,
    val upserts: List<AppEntry>,
    val removed: List<AppKey>,
) {
    val changed: Boolean get() = upserts.isNotEmpty() || removed.isNotEmpty()
}

/**
 * Pure diff of the cached catalog against the platform (FR-LIFE-01, FR-LIFE-03). The platform always
 * wins; only `firstSeenAt` is carried over from the cache.
 */
object Reconciler {
    /**
     * Full pass. [platform] is the complete truth for the profiles that were read; entries of other
     * profiles are dropped (the profile is gone or unavailable).
     */
    fun full(
        previous: List<AppEntry>,
        platform: List<AppEntry>,
        now: Long,
    ): ReconcileResult = diff(previous, platform, now, keep = emptyList())

    /** Partial pass for [packages] of profile [userSerial]: [loaded] replaces exactly those entries. */
    fun packages(
        previous: List<AppEntry>,
        packages: Set<String>,
        userSerial: Long,
        loaded: List<AppEntry>,
        now: Long,
    ): ReconcileResult {
        val (scope, keep) = previous.partition { it.key.userSerial == userSerial && it.packageName in packages }
        return diff(scope, loaded, now, keep)
    }

    private fun diff(
        previous: List<AppEntry>,
        platform: List<AppEntry>,
        now: Long,
        keep: List<AppEntry>,
    ): ReconcileResult {
        val before = previous.associateBy { it.key }
        val upserts = ArrayList<AppEntry>()
        val merged = LinkedHashMap<AppKey, AppEntry>(platform.size)
        for (raw in platform) {
            if (raw.key in merged) continue // the platform listed the same key twice
            val old = before[raw.key]
            val entry = raw.copy(firstSeenAt = old?.firstSeenAt?.takeIf { it > 0 } ?: now)
            merged[entry.key] = entry
            if (entry != old) upserts += entry
        }
        val removed = previous.map { it.key }.filter { it !in merged }
        return ReconcileResult(entries = keep + merged.values, upserts = upserts, removed = removed)
    }
}
