package app.strata.launcher.platform.system

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.StrictMode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.search.SearchIndex
import app.strata.launcher.core.search.Transliterate
import app.strata.launcher.testing.fixtures.SearchFixtures.visible
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * T-SEARCH-05 (R): the Android transliterator on API 29+ and its absence on API 26, through the real index.
 * The index is the only place transliteration is used (ARCHITECTURE §8 step 4).
 */
@RunWith(AndroidJUnit4::class)
class AndroidTextFoldingTest {
    private val tv = visible("テレビ")

    private fun index(): SearchIndex {
        val folding = AndroidTextFolding()
        return SearchIndex.build(
            listOf(tv, visible("Weather")),
            emptyMap(),
            null,
            Transliterate { folding.transliterate(it) },
        )
    }

    private fun SearchIndex.labels(q: String) = rank(q, includeHidden = true).map { it.app.displayLabel }

    @Test
    @Config(sdk = [29])
    fun tSearch05_api29_nativeAndLatinQueries() {
        assertThat(AndroidTextFolding().transliterate("テレビ")).isEqualTo("terebi")
        val idx = index()
        assertThat(idx.labels("テレ")).containsExactly("テレビ")
        assertThat(idx.labels("tere")).containsExactly("テレビ")
    }

    @Test
    @Config(sdk = [26])
    fun tSearch05_api26_nativeOnlyNoCrash() {
        assertThat(AndroidTextFolding().transliterate("テレビ")).isNull()
        val idx = index()
        assertThat(idx.labels("テレ")).containsExactly("テレビ")
        assertThat(idx.labels("tere")).isEmpty()
    }
}

/** T-SEARCH-15 (R): `market://search?q=` with and without a handler; no socket from Strata. */
@RunWith(AndroidJUnit4::class)
class AndroidSystemIntentsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun installStore() {
        val pm = shadowOf(app.packageManager)
        val store = ComponentName("fixture.store", "fixture.store.SearchActivity")
        pm.addActivityIfNotPresent(store)
        pm.addIntentFilterForActivity(
            store,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("market")
            },
        )
    }

    @Test
    fun tSearch15_withHandler() {
        installStore()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectNetwork()
                .penaltyDeath()
                .build(),
        )
        try {
            val intents = AndroidSystemIntents(app)
            assertThat(intents.canSearchStore()).isTrue()
            assertThat(intents.storeSearch("zzq")).isEqualTo(Outcome.Ok(Unit))
            val started = shadowOf(app).nextStartedActivity
            assertThat(started.action).isEqualTo(Intent.ACTION_VIEW)
            assertThat(started.data.toString()).isEqualTo("market://search?q=zzq")
            assertThat(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        } finally {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
        }
    }

    @Test
    fun tSearch15_withoutHandler() {
        val intents = AndroidSystemIntents(app)
        assertThat(intents.canSearchStore()).isFalse()
    }

    @Test
    fun queryIsEncoded() {
        assertThat(AndroidSystemIntents.storeIntent("a b&c").data.toString()).isEqualTo("market://search?q=a%20b%26c")
    }

    /**
     * T-SET-12 (R, platform half): the lock row opens the accessibility settings with Strata's lock service named in the
     * AOSP highlight extras, so the settings app can scroll to it (no public intent opens one service's switch).
     */
    @Test
    fun tSet12_accessibilitySettingsNameTheLockService() {
        assertThat(AndroidSystemIntents(app).accessibilitySettings()).isEqualTo(Outcome.Ok(Unit))
        val started = shadowOf(app).nextStartedActivity
        val ours = ComponentName(app, LockAccessibilityService::class.java).flattenToString()
        assertThat(started.action).isEqualTo(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
        assertThat(started.getStringExtra(AndroidSystemIntents.EXTRA_FRAGMENT_ARG_KEY)).isEqualTo(ours)
        assertThat(
            started
                .getBundleExtra(AndroidSystemIntents.EXTRA_SHOW_FRAGMENT_ARGUMENTS)
                ?.getString(AndroidSystemIntents.EXTRA_FRAGMENT_ARG_KEY),
        ).isEqualTo(ours)
        assertThat(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    /** T-SET-13 (R, platform half): only an app-store install counts as "from a store"; below API 33 never. */
    @Test
    @Config(sdk = [34])
    fun tSet13_installSourceStore() {
        val pm = shadowOf(app.packageManager)
        // (package, initiating, its signing info, originating, installing, update owner, package source)
        pm.setInstallSourceInfo(app.packageName, STORE, null, null, STORE, null, PackageInstaller.PACKAGE_SOURCE_STORE)
        assertThat(InstallSource.installedFromStore(app)).isTrue()
        pm.setInstallSourceInfo(
            app.packageName,
            FILES,
            null,
            null,
            FILES,
            null,
            PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE,
        )
        assertThat(InstallSource.installedFromStore(app)).isFalse()
    }

    @Test
    @Config(sdk = [32])
    fun tSet13_installSourceBelow33() {
        assertThat(InstallSource.installedFromStore(app)).isFalse()
    }

    private companion object {
        // Invented installer packages.
        const val STORE = "com.example.store"
        const val FILES = "com.example.files"
    }
}
