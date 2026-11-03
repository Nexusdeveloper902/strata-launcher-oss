package app.strata.launcher.core.platformapi.apps

import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.AppOverride
import app.strata.launcher.core.model.Catalog
import app.strata.launcher.core.model.ProfileKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app catalog (ARCHITECTURE §4): the persisted snapshot first (FR-LIFE-01), then reconciled with
 * `LauncherApps` on start, on every `onStart` and on every package change (FR-LIST-04, FR-LIFE-03).
 */
interface AppCatalog {
    val catalog: StateFlow<Catalog>

    /** Fire-and-forget reconcile (activity `onStart`, launch failure). Calls are coalesced. */
    fun requestReconcile()
}

/** The assembled A–Z list shared by home, list and search (ARCHITECTURE §4 `AppListAssembler`). */
interface AppListSource {
    val appList: Flow<AppList>
}

/**
 * The launch log in `noBackupFilesDir/launch_log.db` (ARCHITECTURE §4, NFR-PRIV-05): 30-day rolling,
 * pausable (settings field 8), clearable, never any PRIVATE row.
 */
interface LaunchLogRepository {
    /** Records a launch unless the log is paused or [profileKind] is PRIVATE. */
    suspend fun record(
        key: AppKey,
        profileKind: ProfileKind,
    )

    /** Most recently launched distinct keys, newest first. */
    suspend fun recents(limit: Int): List<AppKey>

    /** Launch counts in the last 30 days. */
    suspend fun counts(): Map<AppKey, Int>

    suspend fun setPaused(paused: Boolean)

    /** Deletes every event at once (FR-SET-05 "Clear history"). */
    suspend fun clear()

    /** Emits after every write or clear, so recents can be re-queried. */
    val changes: Flow<Unit>
}

/** Limits of FR-APPS-05 (custom labels). */
object LabelRules {
    const val MAX_LENGTH: Int = 60

    /** Trimmed label, or null when it is empty after trimming (an empty name is not saved, J-06). */
    fun normalize(raw: String): String? = raw.trim().take(MAX_LENGTH).ifEmpty { null }
}

/**
 * Hidden state and custom labels (ARCHITECTURE §4 `OverridesRepository`, `app_override`). Keyed by component +
 * user serial (FR-APPS-04). Writes for a PRIVATE-profile app are rejected (no-op).
 */
interface OverridesRepository {
    val overrides: Flow<Map<AppKey, AppOverride>>

    suspend fun setHidden(
        key: AppKey,
        profileKind: ProfileKind,
        hidden: Boolean,
    )

    /** FR-APPS-05: [label] is normalised with [LabelRules]; null or blank resets to the system label. */
    suspend fun setCustomLabel(
        key: AppKey,
        profileKind: ProfileKind,
        label: String?,
    )
}

/** Outcome of [FavoritesRepository.add]. */
enum class AddFavoriteResult {
    ADDED,
    ALREADY_FAVORITE,
    FULL,

    /** The app is hidden or missing (FR-APPS-04: hidden apps leave favourites); nothing was stored. */
    NOT_SHOWN,
}

/**
 * Favourites order (ARCHITECTURE §4 `FavoritesRepository`, Room `favorite`). The cap of [MAX] counts the
 * favourites that are shown ([add]'s `isShown`): hidden or uninstalled apps keep their slot for a restore
 * (T-APPS-06) but never block a new favourite. Reorder is one transaction.
 */
interface FavoritesRepository {
    /** Every stored favourite in order, including hidden and missing apps. */
    val favorites: Flow<List<AppKey>>

    suspend fun add(
        key: AppKey,
        isShown: (AppKey) -> Boolean,
    ): AddFavoriteResult

    suspend fun remove(key: AppKey)

    /**
     * Replaces [old] with [new] at the same position (the FR-FAV-01 "Replace…" choice). `old == new` changes
     * nothing; a stale [old] (no longer stored) falls back to [add]'s rules, including the cap on shown favourites.
     */
    suspend fun replace(
        old: AppKey,
        new: AppKey,
        isShown: (AppKey) -> Boolean,
    )

    /**
     * Applies a new order of the shown favourites ([order]). Stored keys not in [order] (hidden or missing)
     * keep their relative order after them.
     */
    suspend fun reorder(order: List<AppKey>)

    /**
     * Saves an Edit-favourites session in one atomic write (FR-FAV-02): [edited] are the favourites the session
     * started with (the shown ones), [order] the result. See [merge].
     */
    suspend fun saveEdit(
        order: List<AppKey>,
        edited: Set<AppKey>,
    )

    companion object {
        /** FR-FAV-01. */
        const val MAX: Int = 12

        /**
         * The stored order after an edit: [order] first; then stored keys the session did not see (hidden or
         * missing apps, and favourites added meanwhile elsewhere), in their stored order. Keys of [edited] that
         * are not in [order] were removed.
         */
        fun merge(
            current: List<AppKey>,
            order: List<AppKey>,
            edited: Set<AppKey>,
        ): List<AppKey> {
            val head = order.distinct()
            return head + current.filter { it !in edited && it !in head }
        }
    }
}
