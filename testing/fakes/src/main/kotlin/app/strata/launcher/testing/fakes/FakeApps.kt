package app.strata.launcher.testing.fakes

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.model.AppEntry
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.Catalog
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.platformapi.apps.AppCatalog
import app.strata.launcher.core.platformapi.apps.AppCatalogSource
import app.strata.launcher.core.platformapi.apps.AppLauncher
import app.strata.launcher.core.platformapi.apps.AppListSource
import app.strata.launcher.core.platformapi.apps.CatalogLoad
import app.strata.launcher.core.platformapi.apps.LaunchError
import app.strata.launcher.core.platformapi.apps.LaunchLogRepository
import app.strata.launcher.core.platformapi.apps.LaunchOrigin
import app.strata.launcher.core.platformapi.apps.LaunchResult
import app.strata.launcher.core.platformapi.apps.PackageChange
import app.strata.launcher.core.platformapi.apps.Profile
import app.strata.launcher.core.platformapi.apps.ProfileSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory `LauncherApps`. [apps] is the installed set; [emit] simulates callbacks. [loadDelayMs]
 * delays [loadAll] (T-LIFE-01).
 */
class FakeAppCatalogSource(
    apps: List<AppEntry> = emptyList(),
    var loadDelayMs: Long = 0L,
) : AppCatalogSource {
    @Volatile var apps: List<AppEntry> = apps

    var loadAllCalls: Int = 0
        private set

    private val events = MutableSharedFlow<PackageChange>(extraBufferCapacity = 64)

    override val changes: Flow<PackageChange> = events

    override suspend fun loadAll(): CatalogLoad {
        loadAllCalls++
        if (loadDelayMs > 0) delay(loadDelayMs)
        val current = apps
        return CatalogLoad(current, current.mapTo(HashSet()) { it.key.userSerial } + MAIN_SERIAL)
    }

    override suspend fun loadPackage(
        packageName: String,
        userSerial: Long,
    ): List<AppEntry> = apps.filter { it.packageName == packageName && it.key.userSerial == userSerial }

    /** Installs [entry] and fires `onPackageAdded`. */
    fun install(entry: AppEntry) {
        apps = apps.filterNot { it.key == entry.key } + entry
        events.tryEmit(PackageChange.Packages(listOf(entry.packageName), entry.key.userSerial))
    }

    /** Removes every activity of [packageName] in [userSerial] and fires `onPackageRemoved`. */
    fun uninstall(
        packageName: String,
        userSerial: Long = MAIN_SERIAL,
    ) {
        apps = apps.filterNot { it.packageName == packageName && it.key.userSerial == userSerial }
        events.tryEmit(PackageChange.Packages(listOf(packageName), userSerial))
    }

    /** Changes the installed set without a callback (as while the launcher was stopped). */
    fun silentlySet(entries: List<AppEntry>) {
        apps = entries
    }

    fun emit(change: PackageChange) {
        events.tryEmit(change)
    }

    companion object {
        const val MAIN_SERIAL = 0L
    }
}

/** Records launches; [failing] keys fail with [failure]. */
class FakeAppLauncher : AppLauncher {
    val launches = mutableListOf<Pair<AppKey, LaunchOrigin?>>()
    val appInfoRequests = mutableListOf<AppKey>()
    val failing = mutableSetOf<AppKey>()
    var failure: LaunchError = LaunchError.NOT_FOUND

    override fun launch(
        key: AppKey,
        origin: LaunchOrigin?,
    ): LaunchResult {
        launches += key to origin
        return if (key in failing) Outcome.Err(failure) else Outcome.Ok(Unit)
    }

    override fun openAppInfo(
        key: AppKey,
        origin: LaunchOrigin?,
    ): LaunchResult {
        appInfoRequests += key
        return Outcome.Ok(Unit)
    }

    val uninstallRequests = mutableListOf<AppKey>()

    override fun requestUninstall(key: AppKey): LaunchResult {
        uninstallRequests += key
        return Outcome.Ok(Unit)
    }
}

class FakeProfileSource(
    initial: List<Profile> = listOf(Profile(0L, ProfileKind.MAIN, quietMode = false, available = true)),
) : ProfileSource {
    override val profiles: MutableStateFlow<List<Profile>> = MutableStateFlow(initial)
}

/** A catalog whose state tests set directly. */
class FakeAppCatalog(initial: Catalog = Catalog.EMPTY) : AppCatalog {
    override val catalog: MutableStateFlow<Catalog> = MutableStateFlow(initial)
    var reconcileRequests: Int = 0
        private set

    override fun requestReconcile() {
        reconcileRequests++
    }
}

class FakeAppListSource(initial: AppList = AppList.EMPTY) : AppListSource {
    val state: MutableStateFlow<AppList> = MutableStateFlow(initial)
    override val appList: StateFlow<AppList> = state
}

/** In-memory launch log with the same pause/PRIVATE rules as the Room implementation. */
class FakeLaunchLogRepository(private val clock: FakeClock = FakeClock()) : LaunchLogRepository {
    val events = mutableListOf<Pair<AppKey, Long>>()
    var paused: Boolean = false
    private val changeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    override val changes: Flow<Unit> = changeEvents

    override suspend fun record(
        key: AppKey,
        profileKind: ProfileKind,
    ) {
        if (paused || profileKind == ProfileKind.PRIVATE) return
        events += key to clock.nowMillis()
        changeEvents.tryEmit(Unit)
    }

    override suspend fun recents(limit: Int): List<AppKey> =
        events
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()
            .take(limit)

    override suspend fun counts(): Map<AppKey, Int> = events.groupingBy { it.first }.eachCount()

    override suspend fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    override suspend fun clear() {
        events.clear()
        changeEvents.tryEmit(Unit)
    }
}
