package app.strata.launcher.core.data.launchlog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.data.LazyResource
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.Settings
import app.strata.launcher.testing.fakes.FakeClock
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Launch log (ARCHITECTURE §4, NFR-PRIV-05): write, recents, 30-day counts, pause, clear, no PRIVATE rows. */
@RunWith(AndroidJUnit4::class)
class LaunchLogRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db =
        Room
            .inMemoryDatabaseBuilder(
                context,
                LaunchLogDatabase::class.java,
            ).allowMainThreadQueries()
            .build()
    private val clock = FakeClock(now = 100L * DAY)
    private val settings = FakeSettingsRepository()
    private val repo = RoomLaunchLogRepository(LazyResource(Dispatchers.IO) { db }, settings, clock)
    private val a = AppKey("a/a.Main", 0)
    private val b = AppKey("b/b.Main", 0)

    @After
    fun tearDown() = db.close()

    @Test
    fun recentsAndCounts() =
        runBlocking<Unit> {
            repo.record(a, ProfileKind.MAIN)
            clock.advanceBy(1_000)
            repo.record(b, ProfileKind.MAIN)
            clock.advanceBy(1_000)
            repo.record(a, ProfileKind.MAIN)
            assertThat(repo.recents(10)).containsExactly(a, b).inOrder()
            assertThat(repo.recents(1)).containsExactly(a)
            assertThat(repo.counts()).containsExactly(a, 2, b, 1)
        }

    @Test
    fun rollingThirtyDayWindow() =
        runBlocking<Unit> {
            repo.record(a, ProfileKind.MAIN)
            clock.advanceBy(31 * DAY)
            repo.record(b, ProfileKind.MAIN)
            assertThat(repo.counts()).containsExactly(b, 1)
            assertThat(db.dao().size()).isEqualTo(1) // purged on write
        }

    /** T-PRIV-05: day -31 rows are never returned, and are deleted at start-up; day -29 stays. */
    @Test
    fun expiredEventsAreNotReturnedAndArePrunedAtStartUp() =
        runBlocking<Unit> {
            db.dao().insert(
                LaunchEventEntity(component = a.component, userSerial = 0, at = clock.nowMillis() - 31 * DAY),
            )
            db.dao().insert(
                LaunchEventEntity(component = b.component, userSerial = 0, at = clock.nowMillis() - 29 * DAY),
            )
            assertThat(repo.recents(10)).containsExactly(b)
            assertThat(repo.counts()).containsExactly(b, 1)
            assertThat(db.dao().size()).isEqualTo(2)
            repo.pruneExpired()
            assertThat(db.dao().size()).isEqualTo(1)
            clock.advanceBy(40 * DAY)
            assertThat(repo.recents(10)).isEmpty()
        }

    @Test
    fun privateLaunchesAreNeverWritten() =
        runBlocking<Unit> {
            repo.record(a, ProfileKind.PRIVATE)
            assertThat(db.dao().size()).isEqualTo(0)
        }

    @Test
    fun pauseStopsWritingAndIsStoredInSettings() =
        runBlocking<Unit> {
            repo.setPaused(true)
            assertThat(settings.value.launchLogPaused).isTrue()
            repo.record(a, ProfileKind.MAIN)
            assertThat(db.dao().size()).isEqualTo(0)
            settings.set(Settings(launchLogPaused = false))
            repo.record(a, ProfileKind.MAIN)
            assertThat(db.dao().size()).isEqualTo(1)
        }

    /** FR-SET-05: "Clear history" empties recents immediately. */
    @Test
    fun clearEmptiesEverything() =
        runBlocking<Unit> {
            repo.record(a, ProfileKind.MAIN)
            repo.clear()
            assertThat(repo.recents(10)).isEmpty()
            assertThat(repo.counts()).isEmpty()
        }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
