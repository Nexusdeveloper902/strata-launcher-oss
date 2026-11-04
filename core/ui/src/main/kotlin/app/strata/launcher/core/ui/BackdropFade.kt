package app.strata.launcher.core.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import app.strata.launcher.core.theme.resolve.ResolvedTheme

/**
 * The parameters of `E(W)` (THEME_SYSTEM §7.2) normalised so two backdrops interpolate exactly: `show = false` is
 * the same as `dim = 0`, `backgroundOpacity = 1` (opaque `background`).
 */
@Immutable
data class Backdrop(
    val scrim: Color,
    val dim: Float,
    val background: Color,
    val opacity: Float,
) {
    companion object {
        fun of(
            theme: ResolvedTheme,
            colors: StrataColors,
        ): Backdrop =
            if (theme.wallpaper.show) {
                Backdrop(
                    colors.scrim,
                    theme.wallpaper.dim.toFloat(),
                    colors.background,
                    theme.wallpaper.backgroundOpacity.toFloat(),
                )
            } else {
                Backdrop(colors.scrim, 0f, colors.background, 1f)
            }

        fun lerp(
            a: Backdrop,
            b: Backdrop,
            t: Float,
        ): Backdrop =
            Backdrop(
                scrim = lerp(a.scrim, b.scrim, t),
                dim = a.dim + (b.dim - a.dim) * t,
                background = lerp(a.background, b.background, t),
                opacity = a.opacity + (b.opacity - a.opacity) * t,
            )
    }
}

/**
 * The theme-switch cross-fade (THEME_SYSTEM §12 step 1): only the backdrop fades, and it is read in the draw phase,
 * so a switch costs one recomposition (the new tokens) plus redraws of the backdrop, never a recomposition per frame.
 * Content colours switch at once.
 */
@Stable
class BackdropFade internal constructor(initial: Backdrop) {
    private var from by mutableStateOf(initial)
    private var to by mutableStateOf(initial)
    private var progress by mutableFloatStateOf(1f)

    /** The backdrop to draw now; read it only in a draw lambda. */
    val current: Backdrop get() = if (progress >= 1f) to else Backdrop.lerp(from, to, progress)

    internal val target: Backdrop get() = to

    /** Starts a fade from what is drawn now ([fade]) or jumps to [target]; runs before the frame is drawn. */
    internal fun retarget(
        target: Backdrop,
        fade: Boolean,
    ) {
        from = if (fade) current else target
        to = target
        progress = if (fade) 0f else 1f
    }

    internal suspend fun run(durationMs: Int) {
        if (progress >= 1f) return
        animate(0f, 1f, animationSpec = tween(durationMs)) { value, _ -> progress = value }
    }
}

/** Keeps [BackdropFade] on [target]; fades only when [crossFade] and animations are on. */
@Composable
internal fun rememberBackdropFade(
    target: Backdrop,
    crossFade: Boolean,
    durationMs: Int,
): BackdropFade {
    val fade = remember { BackdropFade(target) }
    val animate = crossFade && durationMs > 0
    // Apply phase of the same frame: the first frame drawn with new tokens already draws the right backdrop start.
    SideEffect { if (fade.target != target) fade.retarget(target, animate) }
    LaunchedEffect(target) { if (animate) fade.run(durationMs) }
    return fade
}
