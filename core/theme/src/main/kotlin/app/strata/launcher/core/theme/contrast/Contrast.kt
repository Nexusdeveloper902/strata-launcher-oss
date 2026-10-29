package app.strata.launcher.core.theme.contrast

import app.strata.launcher.core.theme.color.RgbF
import app.strata.launcher.core.theme.color.Wcag
import app.strata.launcher.core.theme.model.ColorMode
import app.strata.launcher.core.theme.model.ColorRole
import app.strata.launcher.core.theme.model.Palette
import app.strata.launcher.core.theme.model.ThemeDocument
import app.strata.launcher.core.theme.model.Variant
import app.strata.launcher.core.theme.model.WallpaperSpec

/** The pairs of THEME_SYSTEM §7.1. [bg] null = the effective background (§7.2). */
enum class ContrastPair(val fg: ColorRole, val bg: ColorRole?, val minimum: Double) {
    P1(ColorRole.TEXT_PRIMARY, null, 4.5),
    P2(ColorRole.TEXT_SECONDARY, null, 4.5),
    P3(ColorRole.ACCENT, null, 3.0),
    P4(ColorRole.TEXT_PRIMARY, ColorRole.SURFACE, 4.5),
    P5(ColorRole.TEXT_SECONDARY, ColorRole.SURFACE, 4.5),
    P6(ColorRole.ACCENT, ColorRole.SURFACE, 3.0),
    P7(ColorRole.TEXT_PRIMARY, ColorRole.HIGHLIGHT, 4.5),
    P8(ColorRole.ON_ACCENT, ColorRole.ACCENT, 4.5),
}

/** One evaluated pair. */
data class PairResult(val pair: ContrastPair, val ratio: Double) {
    val passes: Boolean get() = ratio >= pair.minimum
}

/** One case of the static check: a variant, with or without pure black. */
data class ContrastCase(val variant: Variant, val pureBlack: Boolean, val results: List<PairResult>) {
    val passes: Boolean get() = results.all { it.passes }
}

/** UI statuses of §7.6. */
enum class ContrastStatus { AA_ANY_WALLPAPER, AA_CURRENT_WALLPAPER, LOW_CONTRAST }

/**
 * WCAG computation of §7.2–7.4. The *static* check covers every possible wallpaper (§7.3);
 * the *runtime* check uses the current wallpaper colours (§7.4, P1–P3 only depend on them).
 */
object Contrast {
    /** `E(W) = over(background, backgroundOpacity, over(scrim, dim, W))`; `background` when `show` is false. */
    fun effectiveBackground(
        p: Palette,
        w: WallpaperSpec,
        wallpaper: RgbF,
    ): RgbF {
        if (!w.show) return RgbF.of(p.background)
        val dimmed = Wcag.over(RgbF.of(p.scrim), w.dim, wallpaper)
        return Wcag.over(RgbF.of(p.background), w.backgroundOpacity, dimmed)
    }

    /** Luminance interval of `E(W)` over all wallpapers (§7.3): the ends are W = black and W = white. */
    fun effectiveRange(
        p: Palette,
        w: WallpaperSpec,
    ): ClosedFloatingPointRange<Double> {
        val a = Wcag.luminance(effectiveBackground(p, w, RgbF.BLACK))
        val b = Wcag.luminance(effectiveBackground(p, w, RgbF.WHITE))
        return minOf(a, b)..maxOf(a, b)
    }

    /**
     * Ratio of [pair] for palette [p]. [wallpaperColors] null → static worst case over every wallpaper;
     * otherwise the minimum over the given wallpaper colours (runtime check).
     */
    fun ratio(
        pair: ContrastPair,
        p: Palette,
        w: WallpaperSpec,
        wallpaperColors: List<Int>? = null,
    ): Double {
        val fl = Wcag.luminance(p[pair.fg])
        val bg = pair.bg
        if (bg != null) return Wcag.ratio(fl, Wcag.luminance(p[bg]))
        if (!w.show) return Wcag.ratio(fl, Wcag.luminance(p.background))
        if (wallpaperColors.isNullOrEmpty()) {
            val r = effectiveRange(p, w)
            return Wcag.worstRatio(fl, r.start, r.endInclusive)
        }
        return wallpaperColors.minOf { Wcag.ratio(fl, Wcag.luminance(effectiveBackground(p, w, RgbF.of(it)))) }
    }

    fun check(
        p: Palette,
        w: WallpaperSpec,
        wallpaperColors: List<Int>? = null,
    ): List<PairResult> = ContrastPair.entries.map { PairResult(it, ratio(it, p, w, wallpaperColors)) }

    fun passes(
        p: Palette,
        w: WallpaperSpec,
        wallpaperColors: List<Int>? = null,
    ): Boolean = ContrastPair.entries.all { ratio(it, p, w, wallpaperColors) >= it.minimum }

    /** Variants a theme can show per its `colors.mode` (§7.1). */
    fun variants(mode: ColorMode): List<Variant> =
        when (mode) {
            ColorMode.LIGHT -> listOf(Variant.LIGHT)
            ColorMode.DARK -> listOf(Variant.DARK)
            ColorMode.SYSTEM, ColorMode.WALLPAPER -> listOf(Variant.LIGHT, Variant.DARK)
        }

    /** Palette of [variant] with pure black applied when asked (dark only). */
    fun palette(
        doc: ThemeDocument,
        variant: Variant,
        pureBlack: Boolean,
    ): Palette {
        val p = doc.colors.palette(variant)
        return if (variant == Variant.DARK && pureBlack) applyPureBlack(p) else p
    }

    fun applyPureBlack(p: Palette): Palette = p.copy(background = PURE_BLACK, scrim = PURE_BLACK)

    private const val PURE_BLACK = 0xFF000000.toInt()

    /**
     * The static (§7.3) check of a theme's fixed palettes: every variant it can show, and for the dark
     * variant also with `pureBlack` toggled on. This is the "AA on any wallpaper" badge and the T-CI-06 gate.
     */
    fun staticCheck(doc: ThemeDocument): List<ContrastCase> {
        val cases = ArrayList<ContrastCase>()
        val vs = variants(doc.colors.mode)
        for (v in vs) {
            cases +=
                ContrastCase(
                    v,
                    v == Variant.DARK && doc.colors.pureBlack,
                    check(palette(doc, v, doc.colors.pureBlack), doc.wallpaper),
                )
        }
        if (Variant.DARK in vs && !doc.colors.pureBlack) {
            cases += ContrastCase(Variant.DARK, true, check(palette(doc, Variant.DARK, true), doc.wallpaper))
        }
        return cases
    }
}
