package app.strata.launcher.core.platformapi.apps

import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppList
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
