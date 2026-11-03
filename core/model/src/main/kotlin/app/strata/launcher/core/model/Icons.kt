package app.strata.launcher.core.model

/**
 * One icon to render (ARCHITECTURE §9). M3 covers system icons only: the pack, shape and tint fields of
 * §9 arrive with icon packs (M5). [lastUpdateTime] is part of the cache key, so an app update invalidates it.
 */
data class IconRequest(
    val key: AppKey,
    val lastUpdateTime: Long,
    val sizePx: Int,
    val densityDpi: Int,
)

/**
 * A rendered icon. [native] is the platform bitmap (an `android.graphics.Bitmap`); only `:core:ui` unwraps it,
 * the same escape hatch as the widget view handle (ARCHITECTURE §3). Pure modules never look inside.
 */
class IconImage(
    val native: Any,
    val width: Int,
    val height: Int,
)

/** Result of an icon request: a bitmap, or the monogram placeholder when the icon cannot be loaded. */
sealed interface IconResult {
    data class Image(val image: IconImage) : IconResult

    data object Monogram : IconResult
}
