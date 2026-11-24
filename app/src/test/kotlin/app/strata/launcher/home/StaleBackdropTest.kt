package app.strata.launcher.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import app.strata.launcher.a11y.KeyboardFixture
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import app.strata.launcher.core.theme.store.ThemePair
import app.strata.launcher.core.theme.store.ThemeState
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.testing.fakes.FakeAppListSource
import app.strata.launcher.testing.fakes.FakeFavoritesRepository
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fakes.FakeThemeRepository
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.Executor

/**
 * T-HOME-16 (FB-017, owner report: "a launcher screen, e.g. the app list, stays in the background of the favourites
 * page instead of the wallpaper, and sticks there when scrolling").
 *
 * Root cause: the renderer decides once, when the window is added, whether its surface is opaque
 * (`ViewRootImpl.enableHardwareAcceleration`: translucent = the window's pixel format is not `OPAQUE`), and an opaque
 * renderer never clears a buffer before drawing into it. Home's format followed its window background, which is opaque
 * under an opaque theme (battery fix), so a home started under such a theme kept an opaque renderer; after a switch to a
 * see-through theme its translucent backdrop was drawn over what the reused buffer still held.
 *
 * Robolectric has no GPU buffers, so the pixel check replays the frames into one reused bitmap with the renderer's rule
 * (clear before drawing only when the window was added with a translucent format), from the real format recorded by
 * the real `ViewRootImpl` and the real composition's pixels (native graphics). Before the fix the window is added
 * `OPAQUE` and the favourites page still shows the list; after it, the reused buffer equals a clean render.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StaleBackdropTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun forgetMemory() {
        HomeWindowMemory.process = null
    }

    private fun withWallpaper(
        show: Boolean,
        opacity: Double,
    ): ThemePair {
        fun set(t: ResolvedTheme) = t.copy(wallpaper = t.wallpaper.copy(show = show, backgroundOpacity = opacity))
        return ThemePair(set(ThemePair.DEFAULT.light), set(ThemePair.DEFAULT.dark))
    }

    private fun graph(repo: FakeThemeRepository) =
        TestAppGraph(
            roleController = FakeRoleController(defaultHome = true),
            settingsRepository = FakeSettingsRepository(Settings(onboardingDone = true)),
            appList = FakeAppListSource(KeyboardFixture.list),
            favorites = FakeFavoritesRepository(listOf(KeyboardFixture.maps.key)),
            themeRepository = repo,
        )

    private fun idle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    /** Lets a few frames run with the real clock, so the wallpaper switch (two frames, then transparent) completes. */
    private fun frames(n: Int) {
        repeat(n) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
        }
        idle()
    }

    private fun showsWallpaper(activity: HomeActivity) =
        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER != 0

    private fun background(activity: HomeActivity) = (activity.window.decorView.background as? ColorDrawable)?.color

    /** The pixel format the window was added with: what the real `ViewRootImpl` holds (it builds its renderer from it). */
    private fun addedFormat(activity: HomeActivity): Int {
        val root = activity.window.decorView.parent
        assertWithMessage(
            "home's window is not added",
        ).that(root?.javaClass?.name).isEqualTo("android.view.ViewRootImpl")
        val field = root!!.javaClass.getDeclaredField("mWindowAttributes").apply { isAccessible = true }
        return (field.get(root) as WindowManager.LayoutParams).format
    }

    /** One frame drawn into [buffer] the way the renderer does: cleared first only when the surface is translucent. */
    private fun present(
        activity: HomeActivity,
        buffer: Bitmap,
        translucent: Boolean,
    ) {
        compose.runOnIdle {
            if (translucent) buffer.eraseColor(Color.TRANSPARENT)
            activity.window.decorView.draw(Canvas(buffer))
        }
    }

    private fun clean(activity: HomeActivity): Bitmap {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        present(activity, bitmap, translucent = true)
        return bitmap
    }

    private fun differing(
        a: Bitmap,
        b: Bitmap,
    ): Int {
        var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        return n
    }

    /**
     * T-HOME-16: home started under an opaque theme, then switched to a see-through one (Settings → Themes), opens the
     * list and goes back to the favourites page. The window was added with a translucent format, and the favourites
     * page drawn into the reused buffer is exactly a clean render: no pixel of the list is left behind the backdrop.
     */
    @Test
    fun tHome16_listDoesNotStayBehindTheFavouritesPage() {
        val repo = FakeThemeRepository(ThemeState(themes = withWallpaper(show = false, opacity = 1.0), loaded = true))
        repo.themes["see-through"] = withWallpaper(show = true, opacity = 0.4)
        graph(repo).install()
        val activity =
            Robolectric
                .buildActivity(HomeActivity::class.java, KeyboardFixture.homeIntent(context))
                .create()
                .start()
                .postCreate(null)
                .resume()
                .visible()
                .get()
        val format = addedFormat(activity)
        idle()
        assertWithMessage("home is not under the opaque theme at start").that(showsWallpaper(activity)).isFalse()
        assertWithMessage("window added with format $format: an opaque renderer never clears its buffers")
            .that(PixelFormat.formatHasAlpha(format))
            .isTrue()
        val translucent = PixelFormat.formatHasAlpha(format)

        compose.mainClock.autoAdvance = false
        repo.apply("see-through")
        frames(60) // the backdrop cross-fade and the wallpaper switch have settled
        compose.mainClock.autoAdvance = true
        assertWithMessage("the see-through theme does not show the wallpaper").that(showsWallpaper(activity)).isTrue()
        assertWithMessage("window background").that(background(activity)).isEqualTo(Color.TRANSPARENT)
        assertWithMessage("format after the switch")
            .that(PixelFormat.formatHasAlpha(activity.window.attributes.format))
            .isTrue()

        val view = activity.window.decorView
        val buffer = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        present(activity, buffer, translucent) // favourites page
        val home = clean(activity)
        compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
        idle()
        present(activity, buffer, translucent) // the list
        val list = clean(activity)
        // Not vacuous: the list frame really differs from the favourites page.
        assertWithMessage(
            "the list frame looks like the favourites page",
        ).that(differing(list, home)).isGreaterThan(MIN_DIFFERENT)

        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        idle()
        present(activity, buffer, translucent) // back on the favourites page
        val stale = differing(buffer, clean(activity))
        assertWithMessage(
            "$stale pixels of an earlier frame remain behind the favourites page",
        ).that(stale).isEqualTo(0)
    }

    /**
     * T-HOME-16: before the active theme is loaded the composition shows the built-in default (opaque). Home must not
     * act on it: under a remembered see-through theme the wallpaper stays requested and the window transparent until
     * the theme is in, and the default's state is never remembered for the next start.
     */
    @Test
    fun tHome16_defaultBeforeLoadDoesNotHideTheWallpaper() {
        val memory =
            HomeWindowMemory(
                prefs = { context.getSharedPreferences("home_window_t16", Context.MODE_PRIVATE) },
                io = Executor { it.run() },
            )
        val remembered = WallpaperWindowState(show = true, background = ThemePair.DEFAULT.light.colors.background)
        memory.save(remembered)
        HomeWindowMemory.process = memory
        val repo = FakeThemeRepository(ThemeState(loaded = false))
        graph(repo).install()
        val activity =
            Robolectric
                .buildActivity(
                    HomeActivity::class.java,
                    KeyboardFixture.homeIntent(context),
                ).setup()
                .get()
        compose.mainClock.autoAdvance = false
        frames(30)
        assertWithMessage("wallpaper dropped before the theme was loaded").that(showsWallpaper(activity)).isTrue()
        assertWithMessage(
            "window background before the theme was loaded",
        ).that(background(activity)).isEqualTo(Color.TRANSPARENT)
        assertWithMessage("the built-in default's state was remembered").that(memory.peek()).isEqualTo(remembered)

        repo.state.value = ThemeState(themes = withWallpaper(show = true, opacity = 0.4), loaded = true)
        frames(30)
        compose.mainClock.autoAdvance = true
        assertWithMessage("wallpaper after load").that(showsWallpaper(activity)).isTrue()
        assertWithMessage("window background after load").that(background(activity)).isEqualTo(Color.TRANSPARENT)
        assertWithMessage("format").that(PixelFormat.formatHasAlpha(activity.window.attributes.format)).isTrue()
    }

    private companion object {
        /** A list row's label alone changes thousands of pixels at xxhdpi. */
        const val MIN_DIFFERENT = 1_000
    }
}
