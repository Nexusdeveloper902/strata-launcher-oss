package app.strata.launcher.core.data.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.data.LazyResource
import app.strata.launcher.core.data.backup.RoomBackupAppStore
import app.strata.launcher.core.data.db.AppOverrideEntity
import app.strata.launcher.core.data.db.StrataDatabase
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppOverride
import app.strata.launcher.core.model.Catalog
import app.strata.launcher.core.model.IconOverride
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.WidgetPlacement
import app.strata.launcher.core.model.WidgetPlacementState
import app.strata.launcher.core.model.WidgetProviderRef
import app.strata.launcher.core.platformapi.backup.AppData
import app.strata.launcher.testing.fakes.FakeAppCatalogSource
import app.strata.launcher.testing.fakes.FakeClock
import app.strata.launcher.testing.fakes.RecordingLogger
import app.strata.launcher.testing.fixtures.AppFixtures
import app.strata.launcher.testing.fixtures.AppFixtures.app
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The catalog repository over a real (in-memory) Room DB with a fake LauncherApps (Robolectric). */
@RunWith(AndroidJUnit4::class)
class AppCatalogRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db =
        Room
            .inMemoryDatabaseBuilder(context, StrataDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = FakeClock(now = 1_000_000L)
    private val source = FakeAppCatalogSource()
    private val logger = RecordingLogger()

    private fun repo() =
        AppCatalogRepository(
            database = LazyResource(Dispatchers.IO) { db },
            source = source,
            clock = clock,
            scope = scope,
            catalogDispatcher = Dispatchers.IO.limitedParallelism(1),
            logger = logger,
        )

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun await(
        repo: AppCatalogRepository,
        predicate: (Catalog) -> Boolean,
    ): Catalog =
        try {
            runBlocking { withTimeout(10_000) { repo.catalog.first(predicate) } }
        } catch (e: Exception) {
            throw AssertionError("catalog=${repo.catalog.value} log=${logger.entries}", e)
        }

    private fun seedSnapshot(entries: List<app.strata.launcher.core.model.AppEntry>) {
        source.silentlySet(entries)
        val r = repo()
        runBlocking { r.reconcileNow() }
    }

    /** T-LIFE-01: the snapshot renders before `loadAll` returns; then the diff is applied. */
    @Test
    fun tLife01_snapshotBeforeReconcile() {
        seedSnapshot(AppFixtures.APPS120)
        val installedNow = AppFixtures.APPS120.drop(1) + app("Zz Test", pkg = "fixture.zz")
        source.silentlySet(installedNow)
        source.loadDelayMs = 2_000

        val repo = repo()
        val started = System.nanoTime()
        repo.start()
        repo.requestReconcile()
        val snapshot = await(repo) { it.entries.isNotEmpty() }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertThat(snapshot.fromSnapshot).isTrue()
        assertThat(snapshot.entries).hasSize(120)
        assertThat(elapsedMs).isLessThan(2_000)

        val reconciled = await(repo) { !it.fromSnapshot }
        assertThat(reconciled.entries.map { it.key }).containsExactlyElementsIn(installedNow.map { it.key })
        // The snapshot was updated for the next cold start. The catalog is published before the disk write
        // (NFR-PERF-06), so wait for the write instead of reading the DB right after the publish.
        val rows =
            runBlocking {
                withTimeout(10_000) {
                    var current = db.snapshotDao().all()
                    while (current.none { it.component == "fixture.zz/fixture.zz.MainActivity" }) {
                        delay(10)
                        current = db.snapshotDao().all()
                    }
                    current
                }
            }
        assertThat(rows.map { it.component }).contains("fixture.zz/fixture.zz.MainActivity")
        assertThat(rows).hasSize(120)
    }

    /** T-LIST-08 / FR-LIST-04 (JVM half): callbacks add and remove rows without a restart. */
    @Test
    fun tList08_liveInstallAndRemove() {
        source.silentlySet(AppFixtures.APPS120)
        val repo = repo()
        repo.start()
        repo.requestReconcile()
        await(repo) { !it.fromSnapshot && it.entries.size == 120 }

        source.install(app("Zz Test", pkg = "fixture.zz"))
        await(repo) { c -> c.entries.any { it.systemLabel == "Zz Test" } }
        source.uninstall("fixture.zz")
        val after = await(repo) { c -> c.entries.none { it.systemLabel == "Zz Test" } }
        assertThat(after.entries).hasSize(120)
        assertThat(source.loadAllCalls).isEqualTo(1) // package events load only that package
    }

    /** FR-LIFE-03: removed apps keep overrides 30 days (`missing_since`), get them back on reinstall, then purge. */
    @Test
    fun overridesSurviveRemovalForThirtyDaysThenPurge() {
        val camera = app("Old Name", pkg = "fixture.cam")
        source.silentlySet(listOf(camera))
        val repo = repo()
        runBlocking {
            repo.reconcileNow()
            db.overrideDao().upsert(AppOverrideEntity(camera.key.component, 0, customLabel = "My cam"))

            source.silentlySet(emptyList())
            repo.reconcileNow()
            assertThat(
                db
                    .overrideDao()
                    .all()
                    .single()
                    .missingSince,
            ).isEqualTo(clock.now)

            clock.advanceBy(29L * DAY)
            source.silentlySet(listOf(camera))
            repo.reconcileNow()
            val back = db.overrideDao().all().single()
            assertThat(back.customLabel).isEqualTo("My cam")
            assertThat(back.missingSince).isNull()

            source.silentlySet(emptyList())
            repo.reconcileNow()
            clock.advanceBy(31L * DAY)
            repo.reconcileNow()
            assertThat(db.overrideDao().all()).isEmpty()
        }
    }

    /**
     * T-BACKUP-08 (R half): a restore left app Y pending (rename + favourite, `missing_since` = restore time). Y installed
     * within 30 days → both apply without user action; installed after 31 days → both purged, never applied.
     */
    @Test
    fun tBackup08_pendingRestoreEntriesApplyOnInstall_orPurgeAfter30Days() {
        val y = app("Y", pkg = "fixture.y")
        for ((wait, applied) in listOf(29L to true, 31L to false)) {
            runBlocking {
                RoomBackupAppStore(LazyResource(Dispatchers.IO) { db }).replace(
                    AppData(
                        overrides = listOf(AppOverride(y.key, customLabel = "Why", missingSince = clock.now)),
                        favorites = listOf(y.key),
                    ),
                )
                source.silentlySet(emptyList())
                val repo = repo()
                repo.reconcileNow()
                clock.advanceBy(wait * DAY)
                source.silentlySet(listOf(y))
                repo.reconcileNow()
                val rows = db.overrideDao().all()
                val favs = db.favoriteDao().all().map { it.component }
                if (applied) {
                    assertThat(rows.single().customLabel).isEqualTo("Why")
                    assertThat(rows.single().missingSince).isNull()
                    assertThat(favs).containsExactly(y.key.component)
                } else {
                    assertThat(rows).isEmpty()
                    assertThat(favs).isEmpty()
                }
            }
        }
    }

    /** A favourite of an uninstalled app gets a marker row, so it expires with the 30 days too; the marker goes on reinstall. */
    @Test
    fun missingFavourite_getsAMarkerThatExpires() {
        val f = app("Fav", pkg = "fixture.fav")
        source.silentlySet(listOf(f))
        val repo = repo()
        runBlocking {
            repo.reconcileNow()
            db.favoriteDao().replaceAll(listOf(f.key.component to f.key.userSerial))
            source.silentlySet(emptyList())
            repo.reconcileNow()
            assertThat(
                db
                    .overrideDao()
                    .all()
                    .single()
                    .missingSince,
            ).isEqualTo(clock.now)
            source.silentlySet(listOf(f))
            repo.reconcileNow()
            assertThat(db.overrideDao().all()).isEmpty()
            assertThat(db.favoriteDao().all()).hasSize(1)
            source.silentlySet(emptyList())
            repo.reconcileNow()
            clock.advanceBy(31L * DAY)
            repo.reconcileNow()
            assertThat(db.favoriteDao().all()).isEmpty()
        }
    }

    /** The restore port replaces the three tables at once and reads them back unchanged. */
    @Test
    fun backupAppStore_roundTrip() {
        val store = RoomBackupAppStore(LazyResource(Dispatchers.IO) { db })
        val data =
            AppData(
                overrides =
                    listOf(
                        AppOverride(AppKey("a.b/.C", 0), hidden = true),
                        AppOverride(
                            AppKey("a.b/.D", 10),
                            customLabel = "Dee",
                            icon = IconOverride("p.q", "d"),
                            missingSince = 5,
                        ),
                    ),
                favorites = listOf(AppKey("a.b/.D", 10), AppKey("a.b/.C", 0)),
                widgets =
                    listOf(
                        WidgetPlacement(-1, WidgetProviderRef("w.x/.W", 0), 2, true, WidgetPlacementState.NEEDS_REBIND),
                    ),
            )
        runBlocking {
            db.overrideDao().upsert(AppOverrideEntity("old/.Old", 0, hidden = true))
            store.replace(data)
            assertThat(store.read()).isEqualTo(data)
            store.replace(AppData())
            assertThat(store.read()).isEqualTo(AppData())
        }
    }

    /** P-5: private-space rows are never written to the snapshot. */
    @Test
    fun privateRowsNeverPersisted() {
        val p = app("Secret", pkg = "fixture.secret", serial = 77, kind = ProfileKind.PRIVATE)
        source.silentlySet(listOf(app("Open"), p))
        val repo = repo()
        runBlocking { repo.reconcileNow() }
        assertThat(repo.catalog.value.entries).hasSize(2)
        assertThat(runBlocking { db.snapshotDao().all() }.map { it.systemLabel }).containsExactly("Open")
    }

    /** Overrides of a profile that was not read (paused, gone) are not marked missing. */
    @Test
    fun unreadProfileOverridesNotMarkedMissing() {
        val work = app("Work mail", pkg = "fixture.wm", serial = 10)
        runBlocking {
            db.overrideDao().upsert(AppOverrideEntity(work.key.component, 10, hidden = true))
            source.silentlySet(emptyList()) // loadedSerials = {0}
            repo().reconcileNow()
            assertThat(
                db
                    .overrideDao()
                    .all()
                    .single()
                    .missingSince,
            ).isNull()
        }
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
