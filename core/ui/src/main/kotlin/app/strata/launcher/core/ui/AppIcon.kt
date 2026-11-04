package app.strata.launcher.core.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.IconImage
import app.strata.launcher.core.model.IconRequest
import app.strata.launcher.core.model.IconResult

/** What a row needs to ask for its system icon: the key and the package's `lastUpdateTime` (cache key, §9). */
data class IconRequestKey(val key: AppKey, val lastUpdateTime: Long)

/**
 * Icon source for [AppIcon] (`:app` adapts `IconProvider` of `:platform:icons`; `:core:ui` may not see
 * `:core:platform-api`, ARCHITECTURE §2.1). Null in [LocalIconLoader] = monogram tiles only (tests, previews).
 */
interface IconLoader {
    fun cached(req: IconRequest): IconImage?

    suspend fun load(req: IconRequest): IconResult
}

val LocalIconLoader = staticCompositionLocalOf<IconLoader?> { null }

/** Loaded icon of one request; [bitmap] is null while the monogram shows. */
@Stable
class AppIconState internal constructor(
    bitmap: ImageBitmap?,
    internal val alpha: Animatable<Float, *>,
) {
    var bitmap by mutableStateOf(bitmap)
        internal set

    /** True while a system icon is shown (it is already badged for work apps, VISUAL_SPEC §7). */
    val isSystemIcon: Boolean get() = bitmap != null
}

/**
 * Loads the icon of [request] at [size] (ARCHITECTURE §9, INTERACTION_SPEC §8): an L1 hit is ready in the first
 * frame; otherwise the monogram shows and the icon fades in when it arrives (an instant swap with reduced motion).
 */
@Composable
fun rememberAppIconState(
    request: IconRequestKey?,
    size: Dp,
): AppIconState {
    val loader = LocalIconLoader.current
    val density = LocalDensity.current
    val sizePx = with(density) { size.roundToPx() }
    val densityDpi = (density.density * DENSITY_BASELINE).toInt()
    val req = request?.let { IconRequest(it.key, it.lastUpdateTime, sizePx, densityDpi) }
    val state =
        remember(req, loader) {
            val initial = req?.let { loader?.cached(it) }?.toImageBitmap()
            AppIconState(initial, Animatable(if (initial != null) 1f else 0f))
        }
    val fadeMs = Strata.theme.animation.durationMs(FADE_MS)
    LaunchedEffect(state) {
        if (req == null || loader == null || state.bitmap != null) return@LaunchedEffect
        val result = loader.load(req)
        if (result is IconResult.Image) {
            state.bitmap = result.image.toImageBitmap()
            if (fadeMs > 0) state.alpha.animateTo(1f, tween(fadeMs)) else state.alpha.snapTo(1f)
        }
    }
    return state
}

/** The icon of [state], or the monogram tile of [label] while there is none. Decorative (no semantics). */
@Composable
fun AppIcon(
    label: String,
    state: AppIconState,
    size: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val bitmap = state.bitmap
    if (bitmap == null) {
        MonogramTile(label = label, size = size, shape = shape, modifier = modifier)
        return
    }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier =
            modifier
                .size(size)
                .graphicsLayer { alpha = state.alpha.value }
                .clearAndSetSemantics {},
    )
}

private fun IconImage.toImageBitmap(): ImageBitmap? = (native as? Bitmap)?.asImageBitmap()

private const val DENSITY_BASELINE = 160f
private const val FADE_MS = 150
