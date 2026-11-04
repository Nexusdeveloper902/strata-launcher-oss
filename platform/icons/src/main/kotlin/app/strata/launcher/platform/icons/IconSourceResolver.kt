package app.strata.launcher.platform.icons

import app.strata.launcher.core.model.IconSource
import app.strata.launcher.core.model.IconStyle
import app.strata.launcher.core.model.IconTintMode

/**
 * Icon source precedence of THEME_SYSTEM §4.6 (ARCHITECTURE §9.1), pure so the render pipeline and the M8 coverage
 * tool (FR-SET-07) cannot disagree. Steps: per-app override, pack mapping, then only when the icon is tinted: Strata
 * glyph, platform monochrome layer (API 33+), desaturated. Untinted icons without override or pack are [IconSource.PLAIN].
 *
 * The glyph set (FR-THEME-35) is M8: until then callers pass `hasGlyph = false`, and nothing else changes when it lands.
 */
object IconSourceResolver {
    /** `AdaptiveIconDrawable.getMonochrome()` (DOC-043). */
    const val MONOCHROME_API = 33

    fun resolve(
        style: IconStyle,
        /** The override's pack is usable and has the drawable. */
        overrideAvailable: Boolean,
        /** The theme's pack maps this component to a drawable that loads. */
        packMapped: Boolean,
        hasGlyph: Boolean,
        hasMonochromeLayer: Boolean,
        apiLevel: Int,
    ): IconSource =
        when {
            overrideAvailable -> IconSource.OVERRIDE
            packMapped -> IconSource.PACK
            !isTinted(style) -> IconSource.PLAIN
            hasGlyph -> IconSource.GLYPH
            hasMonochromeLayer && apiLevel >= MONOCHROME_API -> IconSource.PLATFORM_MONOCHROME
            else -> IconSource.DESATURATED
        }

    /** Tinted = `tint` is monochrome/accent, or the app falls back through `pack.fallback: monochrome`. */
    fun isTinted(style: IconStyle): Boolean = style.tint != IconTintMode.NONE || style.packFallbackMonochrome
}
