package app.strata.launcher.core.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
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
import app.strata.launcher.core.model.IconOverride
import app.strata.launcher.core.model.IconRequest
import app.strata.launcher.core.model.IconResult
import app.strata.launcher.core.model.IconShapeKind
import app.strata.launcher.core.model.IconStyle
import app.strata.launcher.core.model.IconTintMode
import app.strata.launcher.core.theme.model.IconShape
import app.strata.launcher.core.theme.model.IconTint
import app.strata.launcher.core.theme.model.PackFallback
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What a row needs to ask for its icon: the key, the package's `lastUpdateTime` (cache key, §9) and the per-app
 * override (FR-APPS-06). The theme half of the request comes from [Strata.theme].
 */
data class IconRequestKey(
    val key: AppKey,
    val lastUpdateTime: Long,
    val override: IconOverride? = null,
)

/**
 * Icon source for [AppIcon] (`:app` adapts `IconProvider` of `:platform:icons`; `:core:ui` may not see
 * `:core:platform-api`, ARCHITECTURE §2.1). Null in [LocalIconLoader] = monogram tiles only (tests, previews).
 */
interface IconLoader {
    fun cached(req: IconRequest): IconImage?

    suspend fun load(req: IconRequest): IconResult

    /** Bumped when the same request may render differently (pack parsed, installed or removed). */
    val revision: StateFlow<Long> get() = NO_REVISION
}

private val NO_REVISION: StateFlow<Long> = MutableStateFlow(0L)

val LocalIconLoader = staticCompositionLocalOf<IconLoader?> { null }

/** The theme half of an icon request (THEME_SYSTEM §4.6): shape, plate, tint and pack of [theme]. */
fun iconStyleOf(theme: ResolvedTheme): IconStyle {
    val i = theme.icons
    val c = theme.colors
    return IconStyle(
        shape =
            when (i.shape) {
                IconShape.SYSTEM -> IconShapeKind.SYSTEM
                IconShape.CIRCLE -> IconShapeKind.CIRCLE
                IconShape.SQUIRCLE -> IconShapeKind.SQUIRCLE
                IconShape.ROUNDED_SQUARE -> IconShapeKind.ROUNDED_SQUARE
                IconShape.SQUARE -> IconShapeKind.SQUARE
            },
        cornerRadiusPercent = i.cornerRadiusPercent,
        legacyPlate = i.legacyIconPlate,
        plateColor = c.surface,
        tint =
            when (i.tint) {
                IconTint.NONE -> IconTintMode.NONE
                IconTint.MONOCHROME -> IconTintMode.MONOCHROME
                IconTint.ACCENT -> IconTintMode.ACCENT
            },
        // `pack.fallback: monochrome` tints in `textPrimary`, like `tint: monochrome`.
        tintColor = if (i.tint == IconTint.ACCENT) c.accent else c.textPrimary,
        packPackage = i.packPackage,
        packFallbackMonochrome = i.packFallback == PackFallback.MONOCHROME,
    )
}

/** Loaded icon of one request; [bitmap] is null while the monogram shows. */
@Stable
class AppIconState internal constructor(
    bitmap: ImageBitmap?,
    internal val alpha: Animatable<Float, *>,
) {
    var bitmap by mutableStateOf(bitmap)
        internal set

    /** True while a rendered icon is shown (it is already badged for work apps, VISUAL_SPEC §7). */
    val isSystemIcon: Boolean get() = bitmap != null
}

/**
 * Loads the icon of [request] at [size] (ARCHITECTURE §9, INTERACTION_SPEC §8): an L1 hit is ready in the first
 * frame; otherwise the monogram shows and the icon fades in when it arrives (an instant swap with reduced motion).
 * A change of theme, override or pack keeps the current icon until the new one is ready (no monogram flash).
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
    val theme = Strata.theme
    val style = remember(theme.icons, theme.colors) { iconStyleOf(theme) }
    val req = request?.let { IconRequest(it.key, it.lastUpdateTime, sizePx, densityDpi, style, it.override) }
    val revision by (loader?.revision ?: NO_REVISION).collectAsState()
    val state =
        remember(request?.key, loader) {
            val initial = req?.let { loader?.cached(it) }?.toImageBitmap()
            AppIconState(initial, Animatable(if (initial != null) 1f else 0f))
        }
    val fadeMs = Strata.theme.animation.durationMs(FADE_MS)
    LaunchedEffect(state, req, revision) {
        if (req == null || loader == null) return@LaunchedEffect
        val hit = loader.cached(req)?.toImageBitmap()
        if (hit != null) {
            state.bitmap = hit
            state.alpha.snapTo(1f)
            return@LaunchedEffect
        }
        val result = loader.load(req)
        if (result is IconResult.Image) {
            val first = state.bitmap == null
            state.bitmap = result.image.toImageBitmap()
            if (first && fadeMs > 0) state.alpha.animateTo(1f, tween(fadeMs)) else state.alpha.snapTo(1f)
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
