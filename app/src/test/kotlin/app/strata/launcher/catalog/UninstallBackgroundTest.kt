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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        // A full listing in progress (on a phone, every app and label): it read the installed set and is held open until
        // the test opens the gate. Never on the main thread (that call is recorded above instead).
        fullListEntered.incrementAndGet()
        if (Looper.myLooper() != Looper.getMainLooper()) fullListGate?.await(GATE_TIMEOUT_S, TimeUnit.SECONDS)
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

        /** Holds full listings open while set (released by the test, or after [GATE_TIMEOUT_S] as a safety net). */
        @Volatile var fullListGate: CountDownLatch? = null

        /** Full listings that have read the installed set. */
        val fullListEntered = AtomicInteger(0)

        private const val GATE_TIMEOUT_S = 30L

        private fun watch(what: String) {
            if (Looper.myLooper() == Looper.getMainLooper()) mainThreadCalls += what
        }
    }
}

/**
 * FB-009: uninstalling apps must not freeze home. Runs HomeActivity on the real graph (catalog, Room, list pipeline,
 * icons) with the list open (Robolectric, level C).
 * T-LIST-14: a burst of 1 and of 20 removals makes no LauncherApps call on the main thread and the rows leave the list.
 * T-LIST-13: a removal right after home returns from the uninstall dialog (`onStart` asks for a full reconcile,
 * ANDROID_PLATFORM §3.1) leaves the list while that full listing is still held open (NFR-PERF-06: ≤ 1 s, not after
 * the full load); once it completes, the app stays gone.
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
        WatchedLauncherApps.fullListGate = null
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
        WatchedLauncherApps.fullListGate?.countDown()
        WatchedLauncherApps.fullListGate = null
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
        // Installed while the dialog covered home (no callback): only the full pass lists it, which marks its commit.
        WatchedLauncherApps.apps[pkg(APPS)] = info(APPS)
        val gate = CountDownLatch(1)
        WatchedLauncherApps.fullListGate = gate
        val entered = WatchedLauncherApps.fullListEntered.get()
        // The uninstall confirmation covered home; confirming returns to home, whose onStart asks for a full pass.
        controller.pause().stop()
        controller.restart()
        controller.resume()
        pumpUntil { WatchedLauncherApps.fullListEntered.get() > entered }
        // The system removes the package while that full listing is still open.
        remove(listOf(0))
        pumpUntil { !shown(0) }
        assertThat(gate.count).isEqualTo(1L)
        assertThat(WatchedLauncherApps.mainThreadCalls).isEmpty()
        // The full pass completes and commits (the marker appears); the removed app stays gone.
        gate.countDown()

        fun packages() =
            context.appGraph.appCatalog.catalog.value.entries
                .map { it.packageName }
        pumpUntil { pkg(APPS) in packages() }
        assertThat(packages()).doesNotContain(pkg(0))
        assertThat(shown(0)).isFalse()
    }

    private companion object {
        const val APPS = 120
    }
}
