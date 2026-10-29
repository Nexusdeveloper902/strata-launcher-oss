package app.strata.launcher.core.data.list

import app.strata.launcher.core.model.Catalog
import app.strata.launcher.core.platformapi.apps.AppCatalog
import app.strata.launcher.testing.fakes.FakeCollation
import app.strata.launcher.testing.fixtures.AppFixtures.app
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/** FR-LIFE-01: the list is not emitted before the persisted snapshot has been read. */
class AssembledAppListSourceTest {
    private class Catalogs(
        initial: Catalog,
    ) : AppCatalog {
        override val catalog = MutableStateFlow(initial)

        override fun requestReconcile() = Unit
    }

    private fun source(
        catalog: AppCatalog,
        loaded: MutableStateFlow<Boolean>,
        scope: CoroutineScope,
    ) = AssembledAppListSource(
        catalog = catalog,
        overrides = MutableStateFlow(emptyMap()),
        locales = MutableStateFlow(listOf("en")),
        collation = FakeCollation(),
        defaultDispatcher = Dispatchers.Default,
        scope = scope,
        snapshotLoaded = loaded,
    )

    @Test
    fun populatedSnapshot_firstEmissionIsNonEmpty() =
        runBlocking<Unit> {
            val job = Job()
            val catalogs = Catalogs(Catalog.EMPTY)
            val loaded = MutableStateFlow(false)
            val list = source(catalogs, loaded, CoroutineScope(job + Dispatchers.Default)).appList
            val first = async { withTimeout(5_000) { list.first() } }
            kotlinx.coroutines.delay(200) // nothing may be emitted while the snapshot is unread
            assertThat(first.isCompleted).isFalse()
            catalogs.catalog.value = Catalog(listOf(app("Mail"), app("Maps")), fromSnapshot = true)
            loaded.value = true
            assertThat(first.await().appCount).isEqualTo(2)
            job.cancel()
        }

    @Test
    fun emptySnapshot_emitsEmptyListOnceLoaded() =
        runBlocking<Unit> {
            val job = Job()
            val loaded = MutableStateFlow(false)
            val list = source(Catalogs(Catalog.EMPTY), loaded, CoroutineScope(job + Dispatchers.Default)).appList
            val first = async { withTimeout(5_000) { list.first() } }
            kotlinx.coroutines.delay(200)
            assertThat(first.isCompleted).isFalse()
            loaded.value = true
            assertThat(first.await().appCount).isEqualTo(0)
            job.cancel()
        }
}
