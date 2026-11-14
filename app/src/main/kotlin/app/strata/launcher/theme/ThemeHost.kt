package app.strata.launcher.theme

import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.strata.launcher.core.theme.color.RgbF
import app.strata.launcher.core.theme.color.Wcag
import app.strata.launcher.core.theme.contrast.Contrast
import app.strata.launcher.core.theme.model.WallpaperSpec
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import app.strata.launcher.core.theme.store.ThemeRepository
import app.strata.launcher.core.ui.Strata
import app.strata.launcher.core.ui.StrataTheme
import app.strata.launcher.core.ui.ThemeApplyTrace

/**
 * Connects an activity to the theme repository (THEME_SYSTEM §12, ARCHITECTURE §10):
 * - holds the first frame until the active theme is composed, at most [FIRST_FRAME_WAIT_MS] (§12 step 1);
 * - reports the first frame drawn with each applied theme (crash-loop guard, §12 step 4);
 * - offers last-known-good on a later `onStop` once the theme rendered 10 s (§12 step 3); the repository marks the
 *   start good 10 s after the first reported frame (ARCHITECTURE §10);
 * - tints the system bar icons for the effective background (VISUAL_SPEC §6).
 *
 * Frame detection uses Choreographer callbacks, not the Compose frame clock or coroutine delays, so it behaves the
 * same under the Compose test clock (whose virtual time and test-thread frames raced the main thread on CI).
 */
class ThemeHost(
    private val repository: ThemeRepository,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var createdAt = 0L
    private var composedLoaded = false
    private var renderedGeneration = -1
    private var renderedSince = 0L
    private var pendingGeneration = -1

    /** Installs the first-frame gate on [view]. Call from `onCreate`, before or right after `setContent`. */
    fun holdFirstFrame(view: View) {
        createdAt = clock()
        view.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val ready = composedLoaded || clock() - createdAt >= FIRST_FRAME_WAIT_MS
                    if (ready) view.viewTreeObserver.removeOnPreDrawListener(this)
                    // A cancelled draw is rescheduled for the next vsync, so this re-checks every frame.
                    return ready
                }
            },
        )
    }

    /** The themed content: [StrataTheme] over the repository state, plus the frame and window bookkeeping. */
    @Composable
    fun Content(
        window: Window?,
        content: @Composable () -> Unit,
    ) {
        val state by repository.state.collectAsStateWithLifecycle()
        // No cross-fade until a loaded theme has been shown: the cold start draws the theme in its first frame.
        var shownLoaded by remember { mutableStateOf(false) }
        StrataTheme(state.themes, crossFade = shownLoaded) {
            content()
            if (window != null) SystemBarTint(window, Strata.theme)
        }
        SideEffect {
            if (state.loaded) {
                composedLoaded = true
                shownLoaded = true
                reportAfterDraw(state.applyGeneration, state.awaitingFirstFrame)
            }
        }
    }

    /**
     * Two Choreographer frames after the composition that shows [generation]: the frame drawing it is done. Then it
     * is recorded (for last-known-good) and, when the guard waits for it, reported once.
     */
    private fun reportAfterDraw(
        generation: Int,
        awaiting: Boolean,
    ) {
        if (generation == renderedGeneration || generation == pendingGeneration) return
        pendingGeneration = generation
        val choreographer = Choreographer.getInstance()
        choreographer.postFrameCallback {
            // The frame with the composition of [generation] has been drawn (T-THEME-53 trace end).
            ThemeApplyTrace.frameDrawn(generation)
            choreographer.postFrameCallback {
                if (pendingGeneration == generation) pendingGeneration = -1
                onRendered(generation, awaiting)
            }
        }
    }

    private fun onRendered(
        generation: Int,
        awaiting: Boolean,
    ) {
        if (generation != renderedGeneration) {
            renderedGeneration = generation
            renderedSince = clock()
        }
        if (awaiting) repository.onFirstFrame(generation)
    }

    /** `onStop`: a theme that rendered ≥ 10 s becomes last-known-good (§12 step 3). */
    fun onStop() {
        if (renderedSince != 0L && clock() - renderedSince >= STABLE_MS) repository.onStableRender()
    }

    companion object {
        const val FIRST_FRAME_WAIT_MS = 150L
        const val STABLE_MS = 10_000L
    }
}

/**
 * Light status/navigation bar icons on dark backdrops and vice versa, from `E(W)` (VISUAL_SPEC §6); the window
 * background of non-wallpaper windows follows the theme.
 */
@Composable
private fun SystemBarTint(
    window: Window,
    theme: ResolvedTheme,
) {
    val light = backdropIsLight(theme)
    val background = theme.colors.background
    SideEffect {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
        // Windows that do not show the wallpaper: the window background is the theme's, not the platform's light or
        // dark default, so nothing the content leaves unpainted (resize, IME, transitions) shows the wrong colour.
        if (window.attributes.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER == 0) {
            window.setBackgroundDrawable(ColorDrawable(background or OPAQUE))
        }
    }
}

/** Mean luminance of the effective background over a black and a white wallpaper, against mid-grey. */
internal fun backdropIsLight(theme: ResolvedTheme): Boolean {
    val w = theme.wallpaper
    val spec = WallpaperSpec(show = w.show, dim = w.dim, backgroundOpacity = w.backgroundOpacity)
    val dark = Wcag.luminance(Contrast.effectiveBackground(theme.colors, spec, RgbF.BLACK))
    val bright = Wcag.luminance(Contrast.effectiveBackground(theme.colors, spec, RgbF.WHITE))
    return (dark + bright) / 2 > MID_LUMINANCE
}

private const val OPAQUE = 0xFF000000.toInt()

/** Luminance where black and white text have equal contrast. */
private const val MID_LUMINANCE = 0.179
