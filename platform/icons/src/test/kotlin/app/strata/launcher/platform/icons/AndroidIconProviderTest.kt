package app.strata.launcher.platform.icons

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.IconRequest
import app.strata.launcher.core.model.IconResult
import app.strata.launcher.testing.fakes.RecordingLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** System icon cache (ARCHITECTURE §9), Robolectric. Rendering a real icon is covered on the device. */
@RunWith(AndroidJUnit4::class)
class AndroidIconProviderTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val key = AppKey("fixture.maps/fixture.maps.MainActivity", 0)

    /** T-LIFE-06 (R half): an app update (new `lastUpdateTime`) or a density change is a new cache entry. */
    @Test
    fun tLife06_cacheKeyChangesWithUpdate() {
        val v1 = IconRequest(key, lastUpdateTime = 1L, sizePx = 96, densityDpi = 420)
        val p = provider()
        assertThat(p.cacheKey(v1)).isEqualTo(p.cacheKey(v1.copy()))
        assertThat(p.cacheKey(v1.copy(lastUpdateTime = 2L))).isNotEqualTo(p.cacheKey(v1))
        assertThat(p.cacheKey(v1.copy(densityDpi = 480))).isNotEqualTo(p.cacheKey(v1))
        assertThat(p.cacheKey(v1.copy(key = key.copy(userSerial = 10)))).isNotEqualTo(p.cacheKey(v1))
    }

    /** INTERACTION_SPEC §8: an icon that cannot be loaded falls back to the monogram, never an exception. */
    @Test
    fun missingAppGivesMonogram() =
        runBlocking<Unit> {
            val provider = provider()
            val result = provider.icon(IconRequest(key, 1L, 96, 420))
            assertThat(result).isEqualTo(IconResult.Monogram)
            assertThat(provider.cached(IconRequest(key, 1L, 96, 420))).isNull()
        }

    /**
     * NFR-REL-01: building the provider (on main, from `HomeActivity.onCreate`) must not touch the disk. On API 36
     * `getCacheDir()` checks the directory and StrictMode killed every device test (CI run 41576277117).
     */
    @Test
    fun constructionDoesNotTouchTheCacheDir() {
        var cacheDirCalls = 0
        val counting =
            object : android.content.ContextWrapper(app) {
                override fun getApplicationContext(): android.content.Context = this

                override fun getCacheDir(): File {
                    cacheDirCalls++
                    return super.getCacheDir()
                }
            }
        val provider =
            AndroidIconProvider(
                context = counting,
                renderDispatcher = Dispatchers.Unconfined,
                ioDispatcher = Dispatchers.Unconfined,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                logger = RecordingLogger(),
            )
        assertThat(cacheDirCalls).isEqualTo(0)
        provider.cached(IconRequest(key, 1L, 96, 420))
        assertThat(cacheDirCalls).isEqualTo(0) // the L1 lookup is memory only
    }

    /** NFR-PERF-04: the L1 budget is 1/8 of the memory class. */
    @Test
    fun memoryBudgetIsAnEighth() {
        val budget = AndroidIconProvider.defaultMemoryBudget(app)
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        assertThat(budget).isEqualTo(am.memoryClass * 1024 * 1024 / 8)
    }

    private fun provider() =
        AndroidIconProvider(
            context = app,
            renderDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            logger = RecordingLogger(),
            diskDir = { File(app.cacheDir, "icons-test") },
        )
}
