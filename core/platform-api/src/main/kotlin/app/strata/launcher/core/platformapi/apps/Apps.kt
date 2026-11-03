package app.strata.launcher.core.platformapi.apps

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.model.AppEntry
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.ProfileKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** One user profile visible to the launcher (ARCHITECTURE §3, ANDROID_PLATFORM §3.2). */
data class Profile(
    val serial: Long,
    val kind: ProfileKind,
    val quietMode: Boolean,
    val available: Boolean,
)

/**
 * Profiles from `LauncherApps.getProfiles()`, refreshed by runtime-registered profile broadcasts.
 * M1 covers discovery of the main and work profiles; quiet mode and private space arrive in M6.
 */
interface ProfileSource {
    val profiles: StateFlow<List<Profile>>
}

/** What changed on the platform side (`LauncherApps.Callback`, profile broadcasts). */
sealed interface PackageChange {
    /** Added, removed, changed, (un)available or (un)suspended packages of one profile. */
    data class Packages(val packageNames: List<String>, val userSerial: Long) : PackageChange

    /** A profile appeared, disappeared or changed availability: reload everything. */
    data object All : PackageChange
}

/**
 * Launchable activities from `LauncherApps` (FR-APPS-01). Returned entries carry `firstSeenAt = 0`;
 * the catalog repository owns that column. Private-space profiles are not listed before M6.
 */
interface AppCatalogSource {
    /** `getActivityList(null, user)` for each available profile. Main-safe. */
    suspend fun loadAll(): CatalogLoad

    /** `getActivityList(packageName, user)` for one profile; empty when the package is gone. Main-safe. */
    suspend fun loadPackage(
        packageName: String,
        userSerial: Long,
    ): List<AppEntry>

    /** Hot stream of change events; registered while collected. */
    val changes: Flow<PackageChange>
}

/** Result of [AppCatalogSource.loadAll]: entries plus the serials of the profiles that were read. */
data class CatalogLoad(val entries: List<AppEntry>, val loadedSerials: Set<Long>)

/** Source rectangle of a launch in screen pixels (ANDROID_PLATFORM §4). */
data class LaunchOrigin(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Typed launch failures (ARCHITECTURE §11). Platform exceptions never escape the adapter. */
enum class LaunchError { NOT_FOUND, DISABLED, PROFILE_PAUSED, SECURITY, UNKNOWN }

typealias LaunchResult = Outcome<Unit, LaunchError>

/** Starts activities for catalog keys (`LauncherApps.startMainActivity`, FR-APPS-02). */
interface AppLauncher {
    /** Called on main from the click path; cheap. */
    fun launch(
        key: AppKey,
        origin: LaunchOrigin?,
    ): LaunchResult

    /** `LauncherApps.startAppDetailsActivity`. */
    fun openAppInfo(
        key: AppKey,
        origin: LaunchOrigin?,
    ): LaunchResult

    /**
     * FR-APPS-07: the system `ACTION_DELETE` flow for the key's package and user (needs
     * `REQUEST_DELETE_PACKAGES`). The row disappears when the platform reports the removal.
     */
    fun requestUninstall(key: AppKey): LaunchResult
}
