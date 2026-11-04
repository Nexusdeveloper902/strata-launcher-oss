package app.strata.launcher.core.theme.resolve

import app.strata.launcher.core.theme.color.MaterialRoles

/** `WallpaperColors` as seen by the resolver (API 27+). Colours are `0xFFRRGGBB`. */
data class WallpaperColorsInput(
    val primary: Int,
    val secondary: Int? = null,
    val tertiary: Int? = null,
    /** `HINT_SUPPORTS_DARK_TEXT` (API 31+; ignored below). */
    val supportsDarkText: Boolean = false,
) {
    val all: List<Int> get() = listOfNotNull(primary, secondary, tertiary)
}

/** The platform dynamic colour scheme (API 31+), both variants. */
data class DynamicSchemeInput(val light: MaterialRoles, val dark: MaterialRoles)

/**
 * `platformInputs` of THEME_SYSTEM §2. Everything the resolver may depend on is here, so resolution is a
 * pure function (NFR-THEME-09). The system font scale is not an input: the UI applies it on top of
 * `typography.scale` through the platform density, which already multiplies.
 */
data class PlatformInputs(
    val apiLevel: Int,
    val systemDark: Boolean = false,
    val wallpaperColors: WallpaperColorsInput? = null,
    val dynamicScheme: DynamicSchemeInput? = null,
    /** `Settings.Global.ANIMATOR_DURATION_SCALE`. */
    val animatorDurationScale: Double = 1.0,
    /** `ValueAnimator.areAnimatorsEnabled()`. */
    val animatorsEnabled: Boolean = true,
    /** Installed icon packs whose `appfilter.xml` was accepted (or is still being read). */
    val installedIconPacks: Set<String> = emptySet(),
    /** Installed icon packs that were refused (limits, malformed): treated as missing, with their own notice. */
    val unusableIconPacks: Set<String> = emptySet(),
    /** SHA-256 names present in `themes/fonts/`. */
    val userFonts: Set<String> = emptySet(),
    /** SHA-256 names present in `themes/images/`. */
    val images: Set<String> = emptySet(),
    /** IDs of the bundled OFL font catalogue (VISUAL_SPEC). */
    val bundledFonts: Set<String> = emptySet(),
)

/** ARCHITECTURE §4.2 field 17. */
enum class AppearanceOverride { FOLLOW_THEME, SYSTEM, LIGHT, DARK }

/** The global settings the resolver reads (ARCHITECTURE §4.2 fields 5, 17, 18). */
data class ThemeGlobals(
    val appearanceOverride: AppearanceOverride = AppearanceOverride.FOLLOW_THEME,
    val enforceContrast: Boolean = true,
    val hapticsEnabled: Boolean = true,
)
