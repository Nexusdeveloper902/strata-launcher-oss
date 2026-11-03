package app.strata.launcher.core.data.overrides

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.data.LazyResource
import app.strata.launcher.core.data.catalog.AppCatalogRepository
import app.strata.launcher.core.data.db.StrataDatabase
import app.strata.launcher.core.data.list.AppListAssembler
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.Catalog
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.platformapi.apps.AddFavoriteResult
import app.strata.launcher.core.platformapi.apps.FavoritesRepository
import app.strata.launcher.testing.fakes.FakeAppCatalogSource
import app.strata.launcher.testing.fakes.FakeClock
import app.strata.launcher.testing.fakes.FakeCollation
import app.strata.launcher.testing.fakes.RecordingLogger
import app.strata.launcher.testing.fixtures.AppFixtures.app
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** `app_override` and `favorite` writes over a real Room file (Robolectric, level R). */
@RunWith(AndroidJUnit4::class)
class OverridesFavoritesRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var db = open()
    private val collation = FakeCollation()

    private fun open(): StrataDatabase =
        Room
            .databaseBuilder(context, StrataDatabase::class.java, FILE)
            .allowMainThreadQueries()
            .build()

    /** Simulates a process restart: close the file and open it again. */
    private fun reopen() {
        db.close()
        db = open()
    }

    private fun overrides() = RoomOverridesRepository(LazyResource(Dispatchers.IO) { db })

    private fun favorites() = RoomFavoritesRepository(LazyResource(Dispatchers.IO) { db })

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        context.deleteDatabase(FILE)
    }

    /** T-APPS-07: hiding the main copy keys the row by (component, main serial); the work copy stays visible. */
    @Test
    fun tApps07_hideKeyedPerProfile() =
        runBlocking<Unit> {
            val main = app("Mail", pkg = "fixture.mail")
            val work = app("Mail", pkg = "fixture.mail", serial = 10, kind = ProfileKind.WORK)
            overrides().setHidden(main.key, ProfileKind.MAIN, hidden = true)
            val rows = db.overrideDao().all()
            assertThat(rows.map { it.component to it.userSerial }).containsExactly(main.key.component to 0L)
            val list = assemble(Catalog(listOf(main, work), fromSnapshot = false))
            assertThat(list.sections.flatMap { it.apps }.map { it.key }).containsExactly(work.key)
            assertThat(list.hiddenApps.map { it.key }).containsExactly(main.key)
        }

    /** T-APPS-08 (R half): "  Nav  " is trimmed, survives a restart and an app update, and Reset restores "Maps". */
    @Test
    fun tApps08_renameSurvivesRestartAndUpdate() =
        runBlocking<Unit> {
            val maps = app("Maps", pkg = "fixture.maps")
            overrides().setCustomLabel(maps.key, ProfileKind.MAIN, "  Nav  ")
            reopen()
            val updated = maps.copy(systemLabel = "Maps 2", lastUpdateTime = 99L)
            val list = assemble(Catalog(listOf(updated), fromSnapshot = false))
            val row =
                list.sections
                    .single()
                    .apps
                    .single()
            assertThat(row.displayLabel).isEqualTo("Nav")
            assertThat(row.sectionKey).isEqualTo("N")
            assertThat(row.renamed).isTrue()
            assertThat(row.lastUpdateTime).isEqualTo(99L)

            overrides().setCustomLabel(maps.key, ProfileKind.MAIN, null)
            val reset =
                assemble(Catalog(listOf(maps), fromSnapshot = false))
                    .sections
                    .single()
                    .apps
                    .single()
            assertThat(reset.displayLabel).isEqualTo("Maps")
            assertThat(reset.sectionKey).isEqualTo("M")
            assertThat(db.overrideDao().all()).isEmpty() // a row with only defaults is deleted
        }

    /** FR-APPS-05: blank names are not stored; names are capped at 60 characters. */
    @Test
    fun labelRules() =
        runBlocking<Unit> {
            val key = AppKey("p/p.Main", 0)
            overrides().setCustomLabel(key, ProfileKind.MAIN, "   ")
            assertThat(db.overrideDao().all()).isEmpty()
            overrides().setCustomLabel(key, ProfileKind.MAIN, "x".repeat(70))
            assertThat(
                db
                    .overrideDao()
                    .all()
                    .single()
                    .customLabel,
            ).hasLength(60)
        }

    /** P-5: PRIVATE-profile writes are rejected. */
    @Test
    fun privateWritesRejected() =
        runBlocking<Unit> {
            overrides().setHidden(AppKey("p/p.Main", 77), ProfileKind.PRIVATE, hidden = true)
            overrides().setCustomLabel(AppKey("p/p.Main", 77), ProfileKind.PRIVATE, "x")
            assertThat(db.overrideDao().all()).isEmpty()
        }

    /**
     * FR-FAV-02: an Edit-favourites session is saved in one write. Removed favourites go, the new order leads,
     * hidden favourites and one added meanwhile elsewhere are kept after it; positions stay dense.
     */
    @Test
    fun saveEditIsOneAtomicWrite() =
        runBlocking<Unit> {
            val (a, b, c, hidden, other) = listOf("a", "b", "c", "h", "o").map { AppKey("$it/$it.Main", 0) }
            val repo = favorites()
            listOf(a, b, hidden, c).forEach { repo.add(it) { true } }
            repo.add(other) { true } // a favourite added from the sheet while the edit screen was open
            repo.saveEdit(order = listOf(c, a), edited = setOf(a, b, c))
            assertThat(repo.favorites.first()).containsExactly(c, a, hidden, other).inOrder()
            assertThat(db.favoriteDao().all().map { it.position }).containsExactly(0, 1, 2, 3).inOrder()
        }

    /** Writes are serialised read-modify-write transactions: concurrent saves and adds never lose or duplicate rows. */
    @Test
    fun concurrentWritesDoNotInterleave() =
        runBlocking<Unit> {
            val repo = favorites()
            val base = (0 until 6).map { AppKey("p$it/p$it.Main", 0) }
            base.forEach { repo.add(it) { true } }
            val extra = (0 until 6).map { AppKey("x$it/x$it.Main", 0) }
            val jobs =
                extra.mapIndexed { i, key ->
                    scope.launch {
                        if (i % 2 == 0) repo.add(key) { true } else repo.saveEdit(base.reversed(), base.toSet())
                    }
                }
            jobs.forEach { it.join() }
            val final = repo.favorites.first()
            assertThat(final).containsNoDuplicates()
            assertThat(final).containsAtLeastElementsIn(base)
            assertThat(final).containsAtLeast(extra[0], extra[2], extra[4])
            assertThat(db.favoriteDao().all().map { it.position }).isEqualTo(final.indices.toList())
        }

    /** T-FAV-02 (R half): a reorder is one transaction and survives a restart. */
    @Test
    fun tFav02_reorderPersistsAcrossRestart() =
        runBlocking<Unit> {
            val (a, b, c) = listOf("a", "b", "c").map { AppKey("$it/$it.Main", 0) }
            val repo = favorites()
            listOf(a, b, c).forEach { repo.add(it) { true } }
            repo.reorder(listOf(c, a, b))
            reopen()
            assertThat(favorites().favorites.first()).containsExactly(c, a, b).inOrder()
            assertThat(db.favoriteDao().all().map { it.position }).containsExactly(0, 1, 2).inOrder()
        }

    /** FR-FAV-01: the cap counts shown favourites only; hidden ones keep their slot; replace keeps the position. */
    @Test
    fun capAndReplace() =
        runBlocking<Unit> {
            val repo = favorites()
            val keys = (0 until 13).map { AppKey("p$it/p$it.Main", 0) }
            keys.take(12).forEach { assertThat(repo.add(it) { true }).isEqualTo(AddFavoriteResult.ADDED) }
            assertThat(repo.add(keys[12]) { true }).isEqualTo(AddFavoriteResult.FULL)
            assertThat(repo.add(keys[0]) { true }).isEqualTo(AddFavoriteResult.ALREADY_FAVORITE)
            // keys[3] hidden: it no longer counts, but its slot is kept for an unhide (T-APPS-06).
            assertThat(repo.add(keys[12]) { it != keys[3] }).isEqualTo(AddFavoriteResult.ADDED)
            assertThat(repo.favorites.first()).hasSize(13)
            repo.remove(keys[12])
            repo.replace(keys[5], keys[12]) { true }
            val now = repo.favorites.first()
            assertThat(now[5]).isEqualTo(keys[12])
            assertThat(now).hasSize(FavoritesRepository.MAX)
            // Reordering the shown subset keeps the others (hidden) after them.
            repo.reorder(listOf(keys[1], keys[0]))
            assertThat(repo.favorites.first().take(2)).containsExactly(keys[1], keys[0]).inOrder()
            assertThat(repo.favorites.first()).hasSize(FavoritesRepository.MAX)
        }

    /** FR-APPS-04: a hidden (not shown) app is rejected, never stored invisibly. */
    @Test
    fun addRejectsAppThatIsNotShown() =
        runBlocking<Unit> {
            val repo = favorites()
            val hidden = AppKey("h/h.Main", 0)
            assertThat(repo.add(hidden) { false }).isEqualTo(AddFavoriteResult.NOT_SHOWN)
            assertThat(repo.favorites.first()).isEmpty()
        }

    /** replace(a, a) keeps the position; a stale old appends under the cap rules and never exceeds 12 shown. */
    @Test
    fun replaceSameAndStaleOld() =
        runBlocking<Unit> {
            val repo = favorites()
            val keys = (0 until 14).map { AppKey("p$it/p$it.Main", 0) }
            keys.take(3).forEach { repo.add(it) { true } }
            repo.replace(keys[0], keys[0]) { true }
            assertThat(repo.favorites.first()).containsExactly(keys[0], keys[1], keys[2]).inOrder()
            repo.replace(keys[13], keys[3]) { true } // stale old with room: appended
            assertThat(repo.favorites.first()).containsExactly(keys[0], keys[1], keys[2], keys[3]).inOrder()
            (4 until 12).forEach { repo.add(keys[it]) { true } }
            repo.replace(keys[13], keys[12]) { true } // stale old, cap reached: nothing added
            assertThat(repo.favorites.first()).hasSize(FavoritesRepository.MAX)
            assertThat(repo.favorites.first()).doesNotContain(keys[12])
        }

    /** T-LIFE-04 (R half): reinstall at day 10 brings name and hidden state back; at day 31 they are purged. */
    @Test
    fun tLife04_reinstallKeepsOverridesWithinThirtyDays() =
        runBlocking<Unit> {
            val clock = FakeClock(now = 1_000_000L)
            val source = FakeAppCatalogSource()
            val cam = app("Camera", pkg = "fixture.cam")
            source.silentlySet(listOf(cam))
            val catalog =
                AppCatalogRepository(
                    database = LazyResource(Dispatchers.IO) { db },
                    source = source,
                    clock = clock,
                    scope = scope,
                    catalogDispatcher = Dispatchers.IO.limitedParallelism(1),
                    logger = RecordingLogger(),
                )
            catalog.reconcileNow()
            overrides().setCustomLabel(cam.key, ProfileKind.MAIN, "Snap")
            overrides().setHidden(cam.key, ProfileKind.MAIN, hidden = true)

            source.silentlySet(emptyList())
            catalog.reconcileNow()
            clock.advanceBy(10 * DAY)
            source.silentlySet(listOf(cam))
            catalog.reconcileNow()
            val back = overrides().overrides.first()[cam.key]
            assertThat(back?.customLabel).isEqualTo("Snap")
            assertThat(back?.hidden).isTrue()

            source.silentlySet(emptyList())
            catalog.reconcileNow()
            clock.advanceBy(31 * DAY)
            catalog.reconcileNow()
            source.silentlySet(listOf(cam))
            catalog.reconcileNow()
            assertThat(overrides().overrides.first()).isEmpty()
        }

    private suspend fun assemble(catalog: Catalog) =
        AppListAssembler.assemble(
            catalog,
            overrides().overrides.first(),
            collation.sections(listOf("en")),
            collation.comparator(listOf("en")),
        )

    private companion object {
        const val FILE = "overrides-test.db"
        const val DAY = 24L * 60 * 60 * 1000
    }
}
