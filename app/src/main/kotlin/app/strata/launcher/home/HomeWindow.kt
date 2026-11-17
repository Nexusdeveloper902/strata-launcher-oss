package app.strata.launcher.home

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.StrictMode
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
     * The state to start in, applied in `onCreate` before `setContent` (before the window is added), so a start never
     * composes a wallpaper it then drops: the wallpaper flag and an opaque or transparent background.
     */
    fun applyInitial(
        window: Window,
        state: WallpaperWindowState,
    ) {
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
 * The last [WallpaperWindowState] home applied, for the next cold start (the theme is not loaded yet then). One small
 * preference file, read once per home `onCreate`; not backed up (it is outside every backup include).
 */
class HomeWindowMemory(context: Context) {
    private val app = context.applicationContext

    private fun prefs() = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Synchronous by design (the window must be set before it is added); one tiny read, allowed explicitly. */
    fun read(): WallpaperWindowState? {
        val policy = StrictMode.allowThreadDiskReads()
        return try {
            val p = prefs()
            if (!p.contains(KEY_SHOW)) null else WallpaperWindowState(p.getBoolean(KEY_SHOW, true), p.getInt(KEY_BG, 0))
        } catch (e: RuntimeException) {
            null
        } finally {
            StrictMode.setThreadPolicy(policy)
        }
    }

    /** Off the calling thread ([SharedPreferences.Editor.apply]). */
    fun save(state: WallpaperWindowState) {
        val policy = StrictMode.allowThreadDiskReads()
        try {
            prefs()
                .edit()
                .putBoolean(KEY_SHOW, state.show)
                .putInt(KEY_BG, state.background)
                .apply()
        } catch (e: RuntimeException) {
            Unit
        } finally {
            StrictMode.setThreadPolicy(policy)
        }
    }

    private companion object {
        const val FILE = "home_window"
        const val KEY_SHOW = "shows_wallpaper"
        const val KEY_BG = "background"
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
) {
    val theme = Strata.theme
    val show = HomeWindow.showsWallpaper(theme.wallpaper)
    val background = theme.colors.background
    val fade: BackdropFade? = Strata.backdropFade
    LaunchedEffect(show, background) {
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
