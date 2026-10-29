package app.strata.launcher.core.theme.resolve

import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.color.MaterialRoles
import app.strata.launcher.core.theme.color.Rgb
import app.strata.launcher.core.theme.color.TonalSpot
import app.strata.launcher.core.theme.color.Wcag
import app.strata.launcher.core.theme.contrast.Contrast
import app.strata.launcher.core.theme.contrast.ContrastEnforcer
import app.strata.launcher.core.theme.contrast.ContrastStatus
import app.strata.launcher.core.theme.model.ColorMode
import app.strata.launcher.core.theme.model.ColorRole
import app.strata.launcher.core.theme.model.FontRef
import app.strata.launcher.core.theme.model.ListEntrance
import app.strata.launcher.core.theme.model.Palette
import app.strata.launcher.core.theme.model.SourceKind
import app.strata.launcher.core.theme.model.ThemeDocument
import app.strata.launcher.core.theme.model.Variant
import app.strata.launcher.core.theme.model.HapticsMode
import app.strata.launcher.core.theme.parse.Issue
import app.strata.launcher.core.theme.parse.IssueCode

/** A resolved theme plus the stage-6/7 warnings (missing references, low contrast). */
data class Resolution(
    val theme: ResolvedTheme,
    val issues: List<Issue>,
    /** True when resolution failed and Strata Default was used instead (§12 step 5). */
    val fellBack: Boolean = false,
)

/**
 * `resolve(themeFile, platformInputs, globalSettings) -> ResolvedTheme` of THEME_SYSTEM §2:
 * built-in defaults ← file fields (already in [ThemeDocument]) ← derived colours (§5.2) ← global
 * overrides (§6) ← contrast enforcement (§7.5). Pure and deterministic (NFR-THEME-09).
 */
object ThemeResolver {
    const val API_WALLPAPER_COLORS = 27
    const val API_DYNAMIC_COLOR = 31

    /**
     * The resolution boundary: never throws. Any exception falls back to Strata Default (§12 step 5,
     * NFR-THEME-01); [onError] lets the caller log it locally.
     */
    fun resolveSafely(
        doc: ThemeDocument,
        platform: PlatformInputs,
        globals: ThemeGlobals = ThemeGlobals(),
        onError: (Throwable) -> Unit = {},
    ): Resolution =
        try {
            resolve(doc, platform, globals)
        } catch (e: Exception) {
            onError(e)
            resolve(StrataDefault.document, platform, globals).copy(fellBack = true)
        } catch (e: StackOverflowError) {
            onError(e)
            resolve(StrataDefault.document, platform, globals).copy(fellBack = true)
        }

    fun resolve(
        doc: ThemeDocument,
        platform: PlatformInputs,
        globals: ThemeGlobals = ThemeGlobals(),
    ): Resolution {
        val issues = ArrayList<Issue>()
        val variant = variant(doc, platform, globals)
        val dark = variant == Variant.DARK
        val fixed = doc.colors.palette(variant)

        // §5.2 palette derivation with fallback chain.
        val source = effectiveSource(doc, platform)
        val derived: MaterialRoles? =
            when (source) {
                SourceKind.FIXED -> null
                SourceKind.SEED -> TonalSpot.scheme(doc.colors.seedColor!!, dark)
                SourceKind.WALLPAPER -> TonalSpot.scheme(platform.wallpaperColors!!.primary, dark)
                SourceKind.DYNAMIC -> platform.dynamicScheme!!.let { if (dark) it.dark else it.light }
            }
        var palette = derived?.let { lockRoles(map(it, dark), fixed, doc.colors.lockedRoles) } ?: fixed
        val locked = if (derived != null) doc.colors.lockedRoles.toMutableSet() else mutableSetOf()
        if (dark && doc.colors.pureBlack) {
            palette = Contrast.applyPureBlack(palette)
            locked += ColorRole.BACKGROUND
            locked += ColorRole.SCRIM
        }

        // §7.5 enforcement: derived palettes always (the user did not choose them), fixed ones per setting.
        val wallpaperColors =
            platform.wallpaperColors?.takeIf { platform.apiLevel >= API_WALLPAPER_COLORS }?.all
        var wallpaper = doc.wallpaper
        var enforced = false
        if (derived != null || globals.enforceContrast) {
            val out = ContrastEnforcer.enforce(palette, wallpaper, wallpaperColors, locked)
            enforced = out.changedFrom(palette, wallpaper)
            palette = out.palette
            wallpaper = out.wallpaper
        }
        val status =
            when {
                Contrast.passes(palette, wallpaper) -> ContrastStatus.AA_ANY_WALLPAPER
                wallpaperColors != null && Contrast.passes(palette, wallpaper, wallpaperColors) -> ContrastStatus.AA_CURRENT_WALLPAPER
                else -> ContrastStatus.LOW_CONTRAST
            }
        if (status == ContrastStatus.LOW_CONTRAST) issues += Issue("/colors", IssueCode.LOW_CONTRAST, "Low-contrast colours")

        // §4.3 image reference.
        val image = doc.wallpaper.image
        val imageSha = image?.sha256?.takeIf { it in platform.images }
        if (image != null && imageSha == null) issues += Issue("/wallpaper/image/sha256", IssueCode.IMAGE_MISSING, "Wallpaper image not on this device")

        // §4.4 fonts.
        val t = doc.typography
        val family = font(t.family, "/typography/family", platform, issues)
        val clockFamily = t.clockFamily?.let { font(it, "/typography/clockFamily", platform, issues) } ?: family

        // §4.6 icon pack.
        val i = doc.icons
        val pack = i.pack
        val packInstalled = pack != null && pack.packageName in platform.installedIconPacks
        if (pack != null && !packInstalled) issues += Issue("/icons/pack/package", IssueCode.ICON_PACK_MISSING, "Icon pack not installed")

        // §4.11 animation.
        val systemOff = !platform.animatorsEnabled || platform.animatorDurationScale <= 0.0
        val animScale = if (systemOff) 0.0 else doc.animation.durationScale * platform.animatorDurationScale

        val c = doc.clock
        val f = doc.favorites
        val theme =
            ResolvedTheme(
                themeId = doc.id,
                variant = variant,
                colorSource = source,
                colors = palette,
                wallpaper = ResolvedWallpaper(wallpaper.show, wallpaper.dim, wallpaper.backgroundOpacity, imageSha),
                typography =
                    ResolvedTypography(
                        family, clockFamily, t.scale, t.labelWeight, t.headingWeight, t.clockWeight, t.letterSpacingEm, t.labelCase,
                        t.smallTextUsesSystemFont,
                    ),
                list =
                    doc.list.let {
                        ResolvedList(it.rowHeightDp ?: it.density.rowHeightDp, it.horizontalPaddingDp, it.itemSpacingDp, it.sectionHeaders, it.showDividers)
                    },
                icons =
                    ResolvedIcons(
                        visible = i.visible,
                        sizeDp = i.sizeDp,
                        shape = i.shape,
                        cornerRadiusPercent = i.cornerRadiusPercent,
                        packPackage = if (packInstalled) pack!!.packageName else null,
                        packFallback = pack?.fallback ?: app.strata.launcher.core.theme.model.PackFallback.SYSTEM,
                        missingPackPackage = if (pack != null && !packInstalled) pack.packageName else null,
                        missingPackDisplayName = if (pack != null && !packInstalled) pack.displayName else null,
                        tint = i.tint,
                        legacyIconPlate = i.legacyIconPlate,
                        labelsVisible = i.labelsVisible || !i.visible,
                    ),
                clock =
                    ResolvedClock(
                        c.visible, c.style, c.hourFormat, c.showSeconds, c.sizeSp, c.alignment, c.date.visible, c.date.skeleton,
                        c.date.sizeSp,
                    ),
                favorites =
                    ResolvedFavorites(f.textScale, f.alignment, f.verticalPosition, f.showIcons, f.showLabels || !f.showIcons, f.spacingDp),
                search = doc.search.let { ResolvedSearch(it.position, it.style, it.cornerRadiusDp, it.showHint) },
                alphabetIndex =
                    doc.alphabetIndex.let { ResolvedAlphabetIndex(it.visibility, it.side, it.glyphs, it.textSizeSp, it.showBubble) },
                animation = ResolvedAnimation(animScale, if (animScale == 0.0) ListEntrance.NONE else doc.animation.listEntrance),
                haptics = ResolvedHaptics(globals.hapticsEnabled && doc.haptics.mode == HapticsMode.FOLLOW_SYSTEM),
                contrast = ResolvedContrast(status, enforced),
            )
        return Resolution(theme, issues)
    }

    /** §5.1 plus the global appearance override of §6. */
    fun variant(
        doc: ThemeDocument,
        platform: PlatformInputs,
        globals: ThemeGlobals,
    ): Variant {
        fun system() = if (platform.systemDark) Variant.DARK else Variant.LIGHT
        return when (globals.appearanceOverride) {
            AppearanceOverride.SYSTEM -> system()
            AppearanceOverride.LIGHT -> Variant.LIGHT
            AppearanceOverride.DARK -> Variant.DARK
            AppearanceOverride.FOLLOW_THEME ->
                when (doc.colors.mode) {
                    ColorMode.LIGHT -> Variant.LIGHT
                    ColorMode.DARK -> Variant.DARK
                    ColorMode.SYSTEM -> system()
                    ColorMode.WALLPAPER -> {
                        val wc = platform.wallpaperColors
                        when {
                            wc == null || platform.apiLevel < API_WALLPAPER_COLORS -> system()
                            platform.apiLevel >= API_DYNAMIC_COLOR -> if (wc.supportsDarkText) Variant.LIGHT else Variant.DARK
                            else -> if (Wcag.luminance(wc.primary) > 0.5) Variant.LIGHT else Variant.DARK
                        }
                    }
                }
        }
    }

    /** §5.2 fallback chain: dynamic → wallpaper → fixed; seed without a valid colour → fixed. */
    fun effectiveSource(
        doc: ThemeDocument,
        platform: PlatformInputs,
    ): SourceKind {
        val hasWallpaper = platform.wallpaperColors != null && platform.apiLevel >= API_WALLPAPER_COLORS
        return when (doc.colors.sourceKind) {
            SourceKind.FIXED -> SourceKind.FIXED
            SourceKind.SEED -> if (doc.colors.seedColor != null) SourceKind.SEED else SourceKind.FIXED
            SourceKind.WALLPAPER -> if (hasWallpaper) SourceKind.WALLPAPER else SourceKind.FIXED
            SourceKind.DYNAMIC ->
                when {
                    platform.dynamicScheme != null && platform.apiLevel >= API_DYNAMIC_COLOR -> SourceKind.DYNAMIC
                    hasWallpaper -> SourceKind.WALLPAPER
                    else -> SourceKind.FIXED
                }
        }
    }

    /** §5.2 mapping from a Material-style scheme to Strata's roles. */
    fun map(
        m: MaterialRoles,
        dark: Boolean,
    ): Palette =
        Palette(
            background = m.surface,
            surface = m.surfaceContainerHigh,
            textPrimary = m.onSurface,
            textSecondary = m.onSurfaceVariant,
            accent = m.primary,
            onAccent = m.onPrimary,
            divider = m.outlineVariant,
            highlight = m.surfaceContainerHighest,
            scrim = if (dark) Rgb.BLACK else Rgb.WHITE,
        )

    private fun lockRoles(
        derived: Palette,
        fixed: Palette,
        locked: Set<ColorRole>,
    ): Palette = locked.fold(derived) { p, r -> p.with(r, fixed[r]) }

    private fun font(
        ref: FontRef,
        path: String,
        platform: PlatformInputs,
        issues: MutableList<Issue>,
    ): ResolvedFont =
        when (ref) {
            FontRef.System -> ResolvedFont.System
            is FontRef.Bundled ->
                if (ref.id in platform.bundledFonts) {
                    ResolvedFont.Bundled(ref.id)
                } else {
                    issues += Issue("$path/id", IssueCode.BUNDLED_FONT_UNKNOWN, "Unknown bundled font")
                    ResolvedFont.System
                }
            is FontRef.User ->
                if (ref.sha256 in platform.userFonts) {
                    ResolvedFont.User(ref.sha256)
                } else {
                    issues += Issue("$path/sha256", IssueCode.FONT_MISSING, "Font not on this device")
                    ResolvedFont.System
                }
        }
}
