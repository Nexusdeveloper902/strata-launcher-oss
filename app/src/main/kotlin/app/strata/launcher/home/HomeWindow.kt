package app.strata.launcher.home

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.strata.launcher.core.model.IconImage
import app.strata.launcher.core.model.IconRequest
import app.strata.launcher.core.model.IconResult
import app.strata.launcher.core.platformapi.icons.IconProvider
import app.strata.launcher.core.theme.resolve.ResolvedWallpaper
import app.strata.launcher.core.ui.IconLoader
import app.strata.launcher.core.ui.SWITCH_FADE_MS
import app.strata.launcher.core.ui.Strata
import app.strata.launcher.core.ui.durationMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

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

    /**
     * Applies [show] to the home window: `FLAG_SHOW_WALLPAPER` and a transparent background while the wallpaper is
     * shown, otherwise no flag and an opaque [background] (what the content leaves unpainted is the theme's colour,
     * never black). Only what differs is touched, so an unchanged state costs no relayout.
     */
    fun applyWallpaper(
        window: Window,
        show: Boolean,
        background: Int,
    ) {
        val flag = WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
        val has = window.attributes.flags and flag != 0
        when {
            show && !has -> window.addFlags(flag)
            !show && has -> window.clearFlags(flag)
        }
        val color = if (show) Color.TRANSPARENT else background or OPAQUE
        val current = (window.decorView.background as? ColorDrawable)?.color
        if (current != color) window.setBackgroundDrawable(ColorDrawable(color))
    }

    private const val OPAQUE = 0xFF000000.toInt()
}

/**
 * Keeps the home window's wallpaper flag on [HomeWindow.showsWallpaper] for the current theme (battery: the default
 * theme covers the wallpaper, so the system need not compose it behind home). Showing it takes effect at once; hiding it
 * on a theme switch waits for the backdrop cross-fade (§12 step 1), so the fade never runs over a vanished wallpaper.
 */
@Composable
internal fun HomeWallpaperWindow(window: Window) {
    val theme = Strata.theme
    val show = HomeWindow.showsWallpaper(theme.wallpaper)
    val background = theme.colors.background
    val fadeMs = theme.animation.durationMs(SWITCH_FADE_MS)
    var shown by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(show, background) {
        if (!show && shown == true && fadeMs > 0) delay(fadeMs.toLong())
        HomeWindow.applyWallpaper(window, show, background)
        shown = show
    }
}

/** `:core:ui`'s [IconLoader] over `:platform:icons` (ARCHITECTURE §2.1: `:core:ui` cannot see platform-api). */
class ProviderIconLoader(private val provider: IconProvider) : IconLoader {
    override fun cached(req: IconRequest): IconImage? = provider.cached(req)

    override suspend fun load(req: IconRequest): IconResult = provider.icon(req)

    override val revision: StateFlow<Long> get() = provider.revision
}
