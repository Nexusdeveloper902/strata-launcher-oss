package app.strata.launcher.core.data.launchlog

import app.strata.launcher.core.common.Clock
import app.strata.launcher.core.data.LazyResource
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.platformapi.apps.LaunchLogRepository
import app.strata.launcher.core.platformapi.backup.LaunchDayCount
import app.strata.launcher.core.platformapi.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * [LaunchLogRepository] over [LaunchLogDatabase]. Events older than 30 days are purged on every write and at
 * start-up, and queries ignore them (rolling window); PRIVATE launches and launches while paused are never written (NFR-PRIV-05).
 */
class RoomLaunchLogRepository(
    private val database: LazyResource<LaunchLogDatabase>,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : LaunchLogRepository {
    private val changeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val changes: Flow<Unit> = changeEvents

    override suspend fun record(
        key: AppKey,
        profileKind: ProfileKind,
    ) {
        if (profileKind == ProfileKind.PRIVATE) return
        if (settings.settings.first().launchLogPaused) return
        val now = clock.nowMillis()
        val dao = database.get().dao()
        dao.insert(LaunchEventEntity(component = key.component, userSerial = key.userSerial, at = now))
        dao.deleteBefore(now - WINDOW_MS)
        changeEvents.tryEmit(Unit)
    }

    override suspend fun recents(limit: Int): List<AppKey> =
        database
            .get()
            .dao()
            .recents(limit, clock.nowMillis() - WINDOW_MS)
            .map { AppKey(it.component, it.userSerial) }

    override suspend fun counts(): Map<AppKey, Int> =
        database
            .get()
            .dao()
            .countsSince(clock.nowMillis() - WINDOW_MS)
            .associate { AppKey(it.component, it.userSerial) to it.n }

    override suspend fun dayCounts(): List<LaunchDayCount> =
        database
            .get()
            .dao()
            .dayCountsSince(clock.nowMillis() - WINDOW_MS)
            .map { LaunchDayCount(AppKey(it.component, it.userSerial), LocalDate.ofEpochDay(it.day).toString(), it.n) }

    /** Deletes events older than the window; called once at start-up so expired rows never linger (NFR-PRIV-05). */
    suspend fun pruneExpired() {
        database.get().dao().deleteBefore(clock.nowMillis() - WINDOW_MS)
    }

    override suspend fun setPaused(paused: Boolean) {
        settings.update { it.copy(launchLogPaused = paused) }
    }

    override suspend fun clear() {
        database.get().dao().deleteAll()
        changeEvents.tryEmit(Unit)
    }

    companion object {
        /** 30-day rolling window (ARCHITECTURE §4). */
        const val WINDOW_MS: Long = 30L * 24 * 60 * 60 * 1000
    }
}
