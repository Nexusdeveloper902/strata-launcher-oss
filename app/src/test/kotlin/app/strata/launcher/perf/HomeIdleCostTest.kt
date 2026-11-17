package app.strata.launcher.perf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.WindowManager
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.AppSection
import app.strata.launcher.core.model.IndexLetter
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import app.strata.launcher.core.theme.store.ThemePair
import app.strata.launcher.core.theme.store.ThemeState
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.home.HomeWindowMemory
import app.strata.launcher.testing.fakes.FakeAppListSource
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeThemeRepository
import app.strata.launcher.testing.fixtures.AppFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.io.File
import java.util.concurrent.Executor

/**
 * T-PERF-13 (NFR-PERF-05, battery): what idle home costs between clock ticks, at the composition level (Robolectric).
 * The device half (frames and CPU time while idle and with the screen off) is T-PERF-09's `BackgroundCostTest`.
 */
@RunWith(AndroidJUnit4::class)
class HomeIdleCostTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val list: AppList by lazy {
        val apps =
            listOf("Alpha", "Beta", "Gamma").map { name ->
                AppFixtures.app(name, pkg = "fixture.${name.lowercase()}").let {
                    VisibleApp(it.key, it.packageName, name, name, name.take(1), it.profileKind, hidden = false)
                }
            }
        AppList(
            sections = apps.map { AppSection(it.sectionKey, listOf(it)) },
            index = apps.mapIndexed { i, a -> IndexLetter(a.sectionKey, i, 1) },
            hiddenCount = 0,
            fromSnapshot = false,
        )
    }

    private fun graph(theme: FakeThemeRepository = FakeThemeRepository()): TestAppGraph {
        val base = TestAppGraph(FakeRoleController(defaultHome = true), onboarded = true)
        return TestAppGraph(
            roleController = base.roleController,
            settingsRepository = base.settingsRepository,
            appList = FakeAppListSource(list),
            themeRepository = theme,
        ).also { it.install() }
    }

    private fun start(): ActivityController<HomeActivity> {
        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(ComponentName(context, HomeActivity::class.java))
        val controller = Robolectric.buildActivity(HomeActivity::class.java, intent).setup()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        return controller
    }

    /** Recompositions applied while the test clock runs [millis] with no input, and whether frame work is pending. */
    private fun idleFor(millis: Long): Pair<Long, Boolean> {
        compose.mainClock.autoAdvance = false
        val recomposers = Recomposer.runningRecomposers.value
        val before = recomposers.sumOf { it.changeCount }
        var t = 0L
        while (t < millis) {
            compose.mainClock.advanceTimeByFrame()
            t += FRAME_MS
        }
        shadowOf(Looper.getMainLooper()).idle()
        val changes = recomposers.sumOf { it.changeCount } - before
        // PendingWork includes a frame awaiter (an animation or a withFrameNanos loop) on the recomposer's clock.
        val pending = recomposers.any { runBlocking { it.state.first() } != Recomposer.State.Idle }
        compose.mainClock.autoAdvance = true
        return changes to pending
    }

    /** T-PERF-13: home at rest (minutes clock) does no composition work and wants no frame between minute ticks. */
    @Test
    fun tPerf13_restIsIdleBetweenMinuteTicks() {
        val g = graph()
        start()
        val (changes, pending) = idleFor(59_000)
        assertThat(changes).isEqualTo(0)
        assertThat(pending).isFalse()
        // One minute tick: the clock recomposes once, then home is idle again.
        g.timeTicks.tick()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        val (after, pendingAfter) = idleFor(59_000)
        assertThat(after).isEqualTo(0)
        assertThat(pendingAfter).isFalse()
    }

    /** T-PERF-13: the open list (and the hidden home layer under it) is idle too. */
    @Test
    fun tPerf13_listIsIdle() {
        graph()
        start()
        compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
        compose.waitForIdle()
        val (changes, pending) = idleFor(30_000)
        assertThat(changes).isEqualTo(0)
        assertThat(pending).isFalse()
    }

    /** T-PERF-13: a stopped home (screen off, another app on top) listens to no clock and no widget updates. */
    @Test
    fun tPerf13_stoppedHomeStopsClockAndWidgets() {
        val g = graph()
        val controller = start()
        assertThat(g.timeTicks.collectors).isEqualTo(1)
        assertThat(g.widgetHost.listening).isTrue()
        controller.pause().stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(g.timeTicks.collectors).isEqualTo(0)
        assertThat(g.widgetHost.listening).isFalse()
        controller.start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(g.timeTicks.collectors).isEqualTo(1)
        assertThat(g.widgetHost.listening).isTrue()
    }

    private fun withWallpaper(
        show: Boolean,
        opacity: Double = 0.0,
    ): ThemePair {
        fun set(t: ResolvedTheme) = t.copy(wallpaper = t.wallpaper.copy(show = show, backgroundOpacity = opacity))
        return ThemePair(set(ThemePair.DEFAULT.light), set(ThemePair.DEFAULT.dark))
    }

    private fun showsWallpaper(activity: HomeActivity) =
        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER != 0

    /**
     * T-PERF-13: with the default theme (`wallpaper.show` false) home's backdrop is opaque, so the window does not ask
     * for the wallpaper (no wallpaper layer composed, and no live wallpaper rendering, behind it) and paints its own
     * background instead.
     */
    @Test
    fun tPerf13_opaqueThemeDoesNotShowTheWallpaper() {
        graph(FakeThemeRepository(ThemeState(themes = withWallpaper(show = false), loaded = true)))
        val activity = start().get()
        assertThat(showsWallpaper(activity)).isFalse()
        assertThat((activity.window.decorView.background as? ColorDrawable)?.alpha).isEqualTo(255)
    }

    private fun background(activity: HomeActivity) = (activity.window.decorView.background as? ColorDrawable)?.color

    /** Lets the repository's new state reach the composition and runs [frames] frames, the clock held. */
    private fun frames(frames: Int) {
        repeat(frames) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * T-PERF-13: hiding the wallpaper (to an opaque theme) keeps the flag while the backdrop cross-fade runs and drops
     * it, with an opaque background, once the fade has settled; showing it again adds the flag at once and keeps the
     * opaque background until the window has drawn with the wallpaper, then turns transparent.
     */
    @Test
    fun tPerf13_wallpaperFlagFollowsTheTheme() {
        val repo = FakeThemeRepository(ThemeState(themes = withWallpaper(show = true, opacity = 0.4), loaded = true))
        repo.themes["opaque"] = withWallpaper(show = true, opacity = 1.0)
        repo.themes["see-through"] = withWallpaper(show = true, opacity = 0.2)
        graph(repo)
        val activity = start().get()
        assertThat(showsWallpaper(activity)).isTrue()
        assertThat(background(activity)).isEqualTo(android.graphics.Color.TRANSPARENT)

        compose.mainClock.autoAdvance = false
        repo.apply("opaque")
        frames(5) // ~80 ms into the 300 ms fade
        assertThat(showsWallpaper(activity)).isTrue()
        assertThat(background(activity)).isEqualTo(android.graphics.Color.TRANSPARENT)
        frames(60) // the fade has settled
        assertThat(showsWallpaper(activity)).isFalse()
        assertThat(background(activity)).isEqualTo(ThemePair.DEFAULT.light.colors.background or OPAQUE)

        repo.apply("see-through")
        frames(1)
        assertThat(showsWallpaper(activity)).isTrue()
        assertThat(background(activity)).isEqualTo(ThemePair.DEFAULT.light.colors.background or OPAQUE)
        frames(5)
        assertThat(showsWallpaper(activity)).isTrue()
        assertThat(background(activity)).isEqualTo(android.graphics.Color.TRANSPARENT)
        compose.mainClock.autoAdvance = true
    }

    /** Holds [HomeWindowMemory]'s disk work until the test runs it, on a thread that is not main. */
    private class HeldIo : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runOffMain() {
            val t = Thread { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
            t.start()
            t.join()
        }
    }

    private var prefsOnMain = 0
    private var prefsOffMain = 0

    private fun memory(io: Executor) =
        HomeWindowMemory(
            prefs = {
                if (Looper.myLooper() == Looper.getMainLooper()) prefsOnMain++ else prefsOffMain++
                context.getSharedPreferences("home_window", Context.MODE_PRIVATE)
            },
            io = io,
        )

    private fun homeIntent() =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setComponent(ComponentName(context, HomeActivity::class.java))

    /**
     * T-PERF-13: a fresh install (no `shared_prefs` directory yet; its first use is a `mkdir`, a disk write that
     * killed home under StrictMode death on API 36) touches no preference on the main thread, neither in `onCreate`
     * nor when home saves its state: all of it runs on the memory's own thread. Until the prefetch is in, the window
     * keeps the manifest default.
     */
    @Test
    fun tPerf13_freshInstallTouchesNoDiskOnMain() {
        File(context.applicationInfo.dataDir, "shared_prefs").deleteRecursively()
        val io = HeldIo()
        HomeWindowMemory.process = memory(io).also { it.prefetch() }
        graph(FakeThemeRepository(ThemeState(themes = withWallpaper(show = false), loaded = false)))
        val created = Robolectric.buildActivity(HomeActivity::class.java, homeIntent()).create().get()
        assertThat(prefsOnMain).isEqualTo(0)
        assertThat(showsWallpaper(created)).isTrue() // prefetch not in: manifest default
        io.runOffMain()
        assertThat(HomeWindowMemory.process!!.peek()).isNull() // nothing remembered on a first run
        assertThat(prefsOnMain).isEqualTo(0)
        assertThat(prefsOffMain).isEqualTo(1)
    }

    /**
     * T-PERF-13: the next start applies what the last run applied in `onCreate`, from the application's prefetch,
     * before anything is composed or the window is added, so an opaque theme never has the wallpaper composed at start.
     */
    @Test
    fun tPerf13_startAppliesTheRememberedWallpaperState() {
        val io = HeldIo()
        HomeWindowMemory.process = memory(io).also { it.prefetch() }
        graph(FakeThemeRepository(ThemeState(themes = withWallpaper(show = false), loaded = true)))
        start().pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        io.runOffMain() // the prefetch and the save
        assertThat(prefsOnMain).isEqualTo(0)
        // A new process: its application prefetches what the last one saved.
        val nextIo = HeldIo()
        HomeWindowMemory.process = memory(nextIo).also { it.prefetch() }
        nextIo.runOffMain()
        graph(FakeThemeRepository(ThemeState(themes = withWallpaper(show = false), loaded = false)))
        val created = Robolectric.buildActivity(HomeActivity::class.java, homeIntent()).create().get()
        assertThat(showsWallpaper(created)).isFalse()
        assertThat((created.window.decorView.background as? ColorDrawable)?.alpha).isEqualTo(255)
        assertThat(prefsOnMain).isEqualTo(0)
    }

    private companion object {
        const val FRAME_MS = 16L
        const val OPAQUE = 0xFF000000.toInt()
    }
}
