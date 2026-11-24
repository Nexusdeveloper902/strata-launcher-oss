package app.strata.launcher.home

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import app.strata.launcher.core.model.IconImage
import app.strata.launcher.core.model.IconRequest
import app.strata.launcher.core.model.IconResult
import app.strata.launcher.core.platformapi.icons.IconProvider
import app.strata.launcher.core.theme.resolve.ResolvedWallpaper
import app.strata.launcher.core.ui.BackdropFade
import app.strata.launcher.core.ui.IconLoader
import app.strata.launcher.core.ui.Strata
import app.strata.launcher.core.ui.backdropFade
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Window rules of the home activity (FR-HOME-08), pure for unit tests. */
object HomeWindow {
    /** Below this smallest width the device is a phone (VISUAL_SPEC §8, FR-HOME-08). */
    const val TABLET_MIN_SW_DP = 600

    /**
     * FR-HOME-08: phones stay portrait unless "Allow rotation" is on; at sw ≥ 600 dp home always follows the
     * orientation (no letterboxing, T-HOME-12).
     */
    fun orientation(
        smallestWidthDp: Int,
        allowRotation: Boolean,
    ): Int =
        if (smallestWidthDp < TABLET_MIN_SW_DP && !allowRotation) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

    /**
     * Whether any of the system wallpaper can be seen through home's backdrop `E(W)` (THEME_SYSTEM §7.2): only when the
     * theme shows it and the `background` layer over it is not opaque. With `wallpaper.show` off (the default) the
     * backdrop is opaque `background`, so asking the window manager for the wallpaper would only keep it composed (and
     * a live wallpaper rendering) behind a window that covers it entirely.
     */
    fun showsWallpaper(wallpaper: ResolvedWallpaper): Boolean = wallpaper.show && wallpaper.backgroundOpacity < 1.0

    /** Adds or clears `FLAG_SHOW_WALLPAPER`; nothing when it already matches (no relayout). */
    fun setShowsWallpaper(
        window: Window,
        show: Boolean,
    ) {
        val has = showsWallpaper(window)
        when {
            show && !has -> window.addFlags(FLAG)
            !show && has -> window.clearFlags(FLAG)
        }
    }

    fun showsWallpaper(window: Window): Boolean = window.attributes.flags and FLAG != 0

    /** Sets the window background to [color] unless it already is that colour. */
    fun setBackground(
        window: Window,
        color: Int,
    ) {
        val current = (window.peekDecorView()?.background ?: window.decorView.background) as? ColorDrawable
        if (current?.color != color) window.setBackgroundDrawable(ColorDrawable(color))
    }

    /** The window background while the wallpaper is hidden: the theme's `background`, opaque. */
    fun opaque(background: Int): Int = background or OPAQUE

    /**
     * FB-017: fixes home's surface format to translucent, whatever the window background is. Call in `onCreate`, before
     * the window is added.
     *
     * Without an explicit format the window takes it from its background drawable, so the opaque background of an
     * opaque theme made the format `OPAQUE`. The renderer decides once, when the window is added, whether the surface
     * is opaque (`ViewRootImpl.enableHardwareAcceleration`: translucent = format is not `OPAQUE`), and an opaque renderer
     * never clears a buffer before drawing it. A later switch to a theme that lets the wallpaper through changes the
     * format only for the compositor, so home's see-through backdrop was drawn over whatever the reused buffer still
     * held: an earlier layer (the app list) stayed visible behind the favourites page and smeared as it scrolled. With a
     * fixed translucent format the renderer clears every frame. The wallpaper flag still follows the theme, so the
     * system composes no wallpaper behind an opaque home (the battery fix stays).
     */
    fun keepTranslucent(window: Window) {
        if (window.attributes.format != PixelFormat.TRANSLUCENT) window.setFormat(PixelFormat.TRANSLUCENT)
    }

    /**
     * The state to start in, applied in `onCreate` before `setContent` (before the window is added), so a start never
     * composes a wallpaper it then drops: the wallpaper flag and an opaque or transparent background. The pixel format
     * stays translucent ([keepTranslucent]).
     */
    fun applyInitial(
        window: Window,
        state: WallpaperWindowState,
    ) {
        keepTranslucent(window)
        setShowsWallpaper(window, state.show)
        setBackground(window, if (state.show) Color.TRANSPARENT else opaque(state.background))
    }

    private const val FLAG = WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
    private const val OPAQUE = 0xFF000000.toInt()
}

/** What the home window needs from the theme before anything is composed. */
data class WallpaperWindowState(
    val show: Boolean,
    val background: Int,
)

/**
 * The last [WallpaperWindowState] home applied, for the next cold start (the theme is not loaded yet then). Every disk
 * access runs on [io], never on the calling thread: the process prefetches the value from `Application.onCreate`
 * ([start]), and home's `onCreate` only [peek]s at what has arrived. Not ready (or a first run) = null: the window
 * keeps the manifest default. One small preference file, outside every backup include.
 */
class HomeWindowMemory(
    private val prefs: () -> SharedPreferences,
    private val io: Executor,
) {
    @Volatile private var state: WallpaperWindowState? = null

    @Volatile private var ready = false

    /** Starts the read on [io]; returns at once. */
    fun prefetch() {
        io.execute {
            val read =
                try {
                    val p = prefs()
                    if (p.contains(KEY_SHOW)) {
                        WallpaperWindowState(p.getBoolean(KEY_SHOW, true), p.getInt(KEY_BG, 0))
                    } else {
                        null
                    }
                } catch (e: RuntimeException) {
                    null
                }
            // A save made meanwhile is newer than the file.
            if (!ready) state = read
            ready = true
        }
    }

    /** The remembered state if the prefetch has finished; no I/O, safe on main. */
    fun peek(): WallpaperWindowState? = if (ready) state else null

    /** Remembers [value] at once for this process and writes it on [io]. */
    fun save(value: WallpaperWindowState) {
        if (ready && state == value) return
        state = value
        ready = true
        io.execute {
            try {
                prefs()
                    .edit()
                    .putBoolean(KEY_SHOW, value.show)
                    .putInt(KEY_BG, value.background)
                    .apply()
            } catch (e: RuntimeException) {
                Unit
            }
        }
    }

    companion object {
        private const val FILE = "home_window"
        private const val KEY_SHOW = "shows_wallpaper"
        private const val KEY_BG = "background"

        /** The process's memory, created and prefetched by [start]; null before it. */
        @Volatile
        var process: HomeWindowMemory? = null
            internal set

        /** From `Application.onCreate`: one daemon thread does the prefetch and the writes. */
        fun start(context: Context): HomeWindowMemory {
            val app = context.applicationContext
            val io =
                Executors.newSingleThreadExecutor { r ->
                    Thread(r, "strata-home-window").apply { isDaemon = true }
                }
            return HomeWindowMemory({ app.getSharedPreferences(FILE, Context.MODE_PRIVATE) }, io).also {
                process = it
                it.prefetch()
            }
        }
    }
}

/**
 * Keeps the home window on [HomeWindow.showsWallpaper] for the current theme (battery: the default theme covers the
 * wallpaper, so the system need not compose it, or keep a live wallpaper rendering, behind home). Home is the only
 * writer of its window background ([app.strata.launcher.theme.ThemeHost] is built with `ownsWindowBackground = false`).
 * - Showing: the flag first; the opaque background stays until the window has drawn twice with the wallpaper behind it,
 *   then turns transparent, so the cross-fade never runs over an empty (black) window.
 * - Hiding: waits until the backdrop cross-fade has settled ([BackdropFade.settled], system animator scale included),
 *   then paints the opaque background and only then drops the flag.
 */
@Composable
internal fun HomeWallpaperWindow(
    window: Window,
    memory: HomeWindowMemory?,
    /**
     * The active theme has been loaded. Before that the composition shows the built-in default, which is not the
     * user's theme: acting on it would drop the wallpaper of a see-through theme for a moment at every cold start and
     * remember the wrong state for the next one.
     */
    themeLoaded: Boolean,
) {
    val theme = Strata.theme
    val show = HomeWindow.showsWallpaper(theme.wallpaper)
    val background = theme.colors.background
    val fade: BackdropFade? = Strata.backdropFade
    LaunchedEffect(show, background, themeLoaded) {
        if (!themeLoaded) return@LaunchedEffect
        val flagged = HomeWindow.showsWallpaper(window)
        if (show) {
            HomeWindow.setShowsWallpaper(window, true)
            if (!flagged) {
                withFrameNanos { }
                withFrameNanos { }
            }
            HomeWindow.setBackground(window, Color.TRANSPARENT)
        } else {
            if (flagged && fade != null) snapshotFlow { fade.settled }.first { it }
            HomeWindow.setBackground(window, HomeWindow.opaque(background))
            HomeWindow.setShowsWallpaper(window, false)
        }
        memory?.save(WallpaperWindowState(show, background))
    }
}

/** `:core:ui`'s [IconLoader] over `:platform:icons` (ARCHITECTURE §2.1: `:core:ui` cannot see platform-api). */
class ProviderIconLoader(private val provider: IconProvider) : IconLoader {
    override fun cached(req: IconRequest): IconImage? = provider.cached(req)

    override suspend fun load(req: IconRequest): IconResult = provider.icon(req)

    override val revision: StateFlow<Long> get() = provider.revision
}
