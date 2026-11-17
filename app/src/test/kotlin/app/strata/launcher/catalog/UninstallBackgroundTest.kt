package app.strata.launcher.catalog

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.appGraph
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.home.HomeActivity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLauncherApps
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** LauncherApps over a fixed app set; records any call made on the main thread, and can make the full listing slow. */
@Implements(LauncherApps::class)
class WatchedLauncherApps : ShadowLauncherApps() {
    @Implementation
    override fun getActivityList(
        packageName: String?,
        user: UserHandle,
    ): List<LauncherActivityInfo> {
        watch("getActivityList($packageName)")
        val all = apps.values.toList()
        if (packageName != null) return all.filter { it.componentName.packageName == packageName }
        // As on a phone with a few hundred apps: listing and loading every label takes a while.
        if (slowFullListMs > 0) Thread.sleep(slowFullListMs)
        return all
    }

    @Implementation
    fun getProfiles(): List<UserHandle> {
        watch("getProfiles")
        return listOf(Process.myUserHandle())
    }

    companion object {
        val mainThreadCalls = CopyOnWriteArrayList<String>()
        val apps = ConcurrentHashMap<String, LauncherActivityInfo>()

        @Volatile var slowFullListMs = 0L

        private fun watch(what: String) {
            if (Looper.myLooper() == Looper.getMainLooper()) mainThreadCalls += what
        }
    }
}

/**
 * FB-009: uninstalling apps must not freeze home. Runs HomeActivity on the real graph (catalog, Room, list pipeline,
 * icons) with the list open (Robolectric, level C).
 * T-LIST-14: a burst of 1 and of 20 removals makes no LauncherApps call on the main thread and the rows leave the list.
 * T-LIST-13: with a slow full listing, a removal right after home returns from the uninstall dialog (`onStart` asks
 * for a full reconcile, ANDROID_PLATFORM §3.1) leaves the list within 1 s (NFR-PERF-06), not after the full load.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [WatchedLauncherApps::class])
class UninstallBackgroundTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var controller: ActivityController<HomeActivity>

    private fun pkg(i: Int) = "fixture.uninstall%03d".format(i)

    /** The label shown in the list: Robolectric's PackageManager resolves activity labels to the package name. */
    private fun label(i: Int) = pkg(i)

    private fun info(i: Int): LauncherActivityInfo {
        val app =
            ApplicationInfo().apply {
                packageName = pkg(i)
            }
        val activity =
            ActivityInfo().apply {
                packageName = pkg(i)
                name = pkg(i) + ".Main"
                applicationInfo = app
                exported = true
            }
        // The platform builds these from a parcel; the hidden constructors are the only way in a JVM test.
        val states =
            Class
                .forName("android.content.pm.IncrementalStatesInfo")
                .getConstructor(
                    Boolean::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                ).newInstance(false, 1f, 0L)
        val internalClass = Class.forName("android.content.pm.LauncherActivityInfoInternal")
        val internal =
            internalClass
                .getConstructor(
                    ActivityInfo::class.java,
                    states.javaClass,
                    UserHandle::class.java,
                    Boolean::class.javaPrimitiveType,
                ).newInstance(activity, states, Process.myUserHandle(), false)
        val constructor = LauncherActivityInfo::class.java.getDeclaredConstructor(Context::class.java, internalClass)
        constructor.isAccessible = true
        return constructor.newInstance(context, internal)
    }

    @Before
    fun setUp() {
        WatchedLauncherApps.apps.clear()
        WatchedLauncherApps.mainThreadCalls.clear()
        WatchedLauncherApps.slowFullListMs = 0
        for (i in 0 until APPS) WatchedLauncherApps.apps[pkg(i)] = info(i)
        runBlocking { context.appGraph.settingsRepository.update { it.copy(onboardingDone = true) } }
        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(ComponentName(context, HomeActivity::class.java))
        controller = Robolectric.buildActivity(HomeActivity::class.java, intent).setup()
        pumpUntil { context.appGraph.appCatalog.catalog.value.entries.size == APPS }
        compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
        compose.waitForIdle()
        // The assembled list reaches the screen a little after the catalog (slower on a loaded test JVM).
        pumpUntil { shown(0) }
    }

    @After
    fun tearDown() {
        WatchedLauncherApps.slowFullListMs = 0
        runCatching { controller.pause().stop().destroy() }
    }

    /** Runs the main looper (the test thread) while background work proceeds; returns the elapsed time in ms. */
    private fun pumpUntil(
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ): Long {
        val start = System.nanoTime()
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            val elapsed = (System.nanoTime() - start) / 1_000_000
            if (elapsed > timeoutMs) throw AssertionError("timed out after $elapsed ms")
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun shown(i: Int): Boolean = compose.onAllNodesWithText(label(i)).fetchSemanticsNodes().isNotEmpty()

    private fun remove(indices: List<Int>) {
        indices.forEach { WatchedLauncherApps.apps.remove(pkg(it)) }
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        indices.forEach { shadowOf(launcherApps).notifyPackageRemoved(pkg(it)) }
    }

    @Test
    fun tList14_removalBurstStaysOffTheMainThread() {
        assertThat(shown(0)).isTrue()
        WatchedLauncherApps.mainThreadCalls.clear()
        remove(listOf(0))
        pumpUntil { !shown(0) }
        val burst = (1..20).toList()
        remove(burst)
        pumpUntil { context.appGraph.appCatalog.catalog.value.entries.size == APPS - 21 }
        compose.waitForIdle()
        assertThat(burst.filter(::shown)).isEmpty()
        assertThat(WatchedLauncherApps.mainThreadCalls).isEmpty()
    }

    @Test
    fun tList13_removalAfterReturningFromTheDialogIsNotBlocked() {
        assertThat(shown(0)).isTrue()
        WatchedLauncherApps.slowFullListMs = SLOW_FULL_LIST_MS
        // The uninstall confirmation covered home; confirming returns to home, whose onStart asks for a full pass.
        controller.pause().stop()
        controller.restart()
        controller.resume()
        shadowOf(Looper.getMainLooper()).idle()
        // The system removes the package while that full listing is still running.
        Thread.sleep(200)
        remove(listOf(0))
        val elapsed = pumpUntil(timeoutMs = 2 * SLOW_FULL_LIST_MS) { !shown(0) }
        assertThat(elapsed).isLessThan(1_000L)
        assertThat(WatchedLauncherApps.mainThreadCalls).isEmpty()
        // Once the full pass has committed, the app stays gone.
        Thread.sleep(SLOW_FULL_LIST_MS)
        pumpUntil { true }
        assertThat(
            context.appGraph.appCatalog.catalog.value.entries
                .map { it.packageName },
        ).doesNotContain(pkg(0))
        assertThat(shown(0)).isFalse()
    }

    private companion object {
        const val APPS = 120
        const val SLOW_FULL_LIST_MS = 3_000L
    }
}
