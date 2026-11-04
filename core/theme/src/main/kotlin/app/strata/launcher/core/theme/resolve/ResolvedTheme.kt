package app.strata.launcher.core.theme.resolve

import app.strata.launcher.core.theme.contrast.ContrastStatus
import app.strata.launcher.core.theme.model.Alignment
import app.strata.launcher.core.theme.model.ClockStyle
import app.strata.launcher.core.theme.model.HourFormat
import app.strata.launcher.core.theme.model.IconShape
import app.strata.launcher.core.theme.model.IconTint
import app.strata.launcher.core.theme.model.IndexGlyphs
import app.strata.launcher.core.theme.model.IndexSide
import app.strata.launcher.core.theme.model.IndexVisibility
import app.strata.launcher.core.theme.model.LabelCase
import app.strata.launcher.core.theme.model.ListEntrance
import app.strata.launcher.core.theme.model.PackFallback
import app.strata.launcher.core.theme.model.Palette
import app.strata.launcher.core.theme.model.SearchPosition
import app.strata.launcher.core.theme.model.SearchStyle
import app.strata.launcher.core.theme.model.SourceKind
import app.strata.launcher.core.theme.model.Variant
import app.strata.launcher.core.theme.model.VerticalPosition

/**
 * The immutable token set the UI renders (THEME_SYSTEM §2). Plain data, structurally comparable, so
 * identical inputs give `==` results (NFR-THEME-09) and the UI can skip recomposition on equal values.
 */
data class ResolvedTheme(
    val themeId: String,
    val variant: Variant,
    /** Source actually used after the fallback chain of §5.2. */
    val colorSource: SourceKind,
    /** Final colours after derivation, locked roles, pure black and enforcement. */
    val colors: Palette,
    val wallpaper: ResolvedWallpaper,
    val typography: ResolvedTypography,
    val list: ResolvedList,
    val icons: ResolvedIcons,
    val clock: ResolvedClock,
    val favorites: ResolvedFavorites,
    val search: ResolvedSearch,
    val alphabetIndex: ResolvedAlphabetIndex,
    val animation: ResolvedAnimation,
    val haptics: ResolvedHaptics,
    val contrast: ResolvedContrast,
)

data class ResolvedWallpaper(
    val show: Boolean,
    val dim: Double,
    val backgroundOpacity: Double,
    /** Present only when the image exists in the image store. Never applied silently (§4.3). */
    val imageSha256: String?,
)

sealed interface ResolvedFont {
    data object System : ResolvedFont

    data class Bundled(val id: String) : ResolvedFont

    data class User(val sha256: String) : ResolvedFont
}

data class ResolvedTypography(
    val family: ResolvedFont,
    val clockFamily: ResolvedFont,
    /** Theme scale; multiplies (never replaces) the system font scale. */
    val scale: Double,
    val labelWeight: Int,
    val headingWeight: Int,
    val clockWeight: Int,
    val letterSpacingEm: Double,
    val labelCase: LabelCase,
    val smallTextUsesSystemFont: Boolean,
)

data class ResolvedList(
    /** Nominal height; rows still grow with the font scale (NFR-THEME-10). */
    val rowHeightDp: Int,
    val horizontalPaddingDp: Int,
    val itemSpacingDp: Int,
    val sectionHeaders: Boolean,
    val showDividers: Boolean,
)

data class ResolvedIcons(
    val visible: Boolean,
    val sizeDp: Int,
    val shape: IconShape,
    val cornerRadiusPercent: Int,
    /** Installed pack to use, or null for system icons. */
    val packPackage: String?,
    /** Used for apps the pack does not cover, and for everything when the pack is missing. */
    val packFallback: PackFallback,
    /** The pack the theme asks for when it is not installed (one-time notice, FR-THEME-25). */
    val missingPackPackage: String?,
    val missingPackDisplayName: String?,
    val tint: IconTint,
    val legacyIconPlate: Boolean,
    /** Forced on when icons are hidden. */
    val labelsVisible: Boolean,
)

data class ResolvedClock(
    val visible: Boolean,
    val style: ClockStyle,
    val hourFormat: HourFormat,
    val showSeconds: Boolean,
    val sizeSp: Int,
    val alignment: Alignment,
    val dateVisible: Boolean,
    val dateSkeleton: String,
    val dateSizeSp: Int,
)

data class ResolvedFavorites(
    val textScale: Double,
    val alignment: Alignment,
    val verticalPosition: VerticalPosition,
    val showIcons: Boolean,
    /** Forced on when both icons and labels are off. */
    val showLabels: Boolean,
    val spacingDp: Int,
)

data class ResolvedSearch(
    val position: SearchPosition,
    val style: SearchStyle,
    val cornerRadiusDp: Int,
    val showHint: Boolean,
)

data class ResolvedAlphabetIndex(
    val visibility: IndexVisibility,
    val side: IndexSide,
    val glyphs: IndexGlyphs,
    val textSizeSp: Int,
    val showBubble: Boolean,
)

data class ResolvedAnimation(
    /** Effective multiplier = theme × system animator scale; 0 when animations are off (§4.11). */
    val durationScale: Double,
    val listEntrance: ListEntrance,
    /**
     * The theme's own factor (0 when animations are off). Compose already applies the system animator scale to
     * its animations (`MotionDurationScale`), so UI durations use base × this to get base × theme × system.
     */
    val themeDurationScale: Double = durationScale,
) {
    val enabled: Boolean get() = durationScale > 0.0
}

data class ResolvedHaptics(val enabled: Boolean)

data class ResolvedContrast(
    /** Status of the colours actually rendered. */
    val status: ContrastStatus,
    /** True when enforcement changed a colour, `dim` or `backgroundOpacity`. */
    val enforced: Boolean,
)
