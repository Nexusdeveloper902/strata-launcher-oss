package app.strata.launcher.core.theme.editor

import app.strata.launcher.core.json.JsonNumber
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.theme.color.Rgb
import app.strata.launcher.core.theme.contrast.Contrast
import app.strata.launcher.core.theme.contrast.ContrastEnforcer
import app.strata.launcher.core.theme.contrast.ContrastPair
import app.strata.launcher.core.theme.model.ColorRole
import app.strata.launcher.core.theme.model.Palette
import app.strata.launcher.core.theme.model.ThemeDocument
import app.strata.launcher.core.theme.model.Variant
import app.strata.launcher.core.theme.model.WallpaperSpec
import app.strata.launcher.core.theme.resolve.AppearanceOverride
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.ThemeGlobals
import app.strata.launcher.core.theme.resolve.ThemeResolver
import java.math.BigDecimal
import java.math.RoundingMode

/** One evaluated pair of THEME_SYSTEM §7.1 for one case (variant, pure black on/off). */
data class PairCheck(
    val variant: Variant,
    val pureBlack: Boolean,
    val pair: ContrastPair,
    val ratio: Double,
) {
    val passes: Boolean get() = ratio >= pair.minimum
}

/** One case the theme can show: the palette and wallpaper the checks ran on, before enforcement. */
data class ContrastCaseInput(
    val variant: Variant,
    val pureBlack: Boolean,
    val palette: Palette,
    val wallpaper: WallpaperSpec,
)

/**
 * The editor's live contrast state (§7.6): every pair for every case the theme can show (§7.1), against every
 * wallpaper (the §7.3 static check, the "AA on any wallpaper" goal).
 */
data class ContrastReport(
    val cases: List<ContrastCaseInput>,
    val checks: List<PairCheck>,
) {
    val allPass: Boolean get() = checks.all { it.passes }

    /** Failing pairs, worst case per (pair, variant), in §7.1 order: the banner lines. */
    val failing: List<PairCheck>
        get() =
            checks
                .filterNot { it.passes }
                .groupBy { it.pair to it.variant }
                .map { (_, v) -> v.minBy { it.ratio } }
                .sortedWith(compareBy({ it.pair.ordinal }, { it.variant.ordinal }))

    /**
     * The chip of a colour control: the worst pair [role] takes part in for [variant], as foreground or background.
     * `background` and `scrim` make up the effective background, so they share P1–P3. Null for `divider` (decorative,
     * exempt) and for roles the theme never shows in [variant].
     */
    fun chip(
        variant: Variant,
        role: ColorRole,
    ): PairCheck? =
        checks
            .filter { it.variant == variant && involves(it.pair, role) }
            .minByOrNull { it.ratio / it.pair.minimum }

    /** Identity of the failing set, for polite announcements that change only when the problems change. */
    val failingKey: String get() = failing.joinToString(",") { "${it.pair}${it.variant.wire.first()}" }

    companion object {
        fun involves(
            pair: ContrastPair,
            role: ColorRole,
        ): Boolean {
            if (pair.fg == role || pair.bg == role) return true
            return pair.bg == null && (role == ColorRole.BACKGROUND || role == ColorRole.SCRIM)
        }
    }
}

/**
 * Contrast checking and the editor's "Fix" (THEME_SYSTEM §7.5, §7.6). Cases are resolved with enforcement off, so
 * they show the theme's own colours (derived palettes are still enforced by the resolver, §5.2); the variant is
 * forced per case so both palettes are checked whatever the system dark setting is.
 */
object EditorContrast {
    fun cases(
        doc: ThemeDocument,
        inputs: PlatformInputs,
        globals: ThemeGlobals = ThemeGlobals(),
    ): List<ContrastCaseInput> {
        val out = ArrayList<ContrastCaseInput>()
        for (v in Contrast.variants(doc.colors.mode)) {
            out += case(doc, v, doc.colors.pureBlack && v == Variant.DARK, inputs, globals)
            if (v == Variant.DARK && !doc.colors.pureBlack) out += case(doc, v, true, inputs, globals)
        }
        return out
    }

    private fun case(
        doc: ThemeDocument,
        variant: Variant,
        pureBlack: Boolean,
        inputs: PlatformInputs,
        globals: ThemeGlobals,
    ): ContrastCaseInput {
        val d =
            if (pureBlack ==
                doc.colors.pureBlack
            ) {
                doc
            } else {
                doc.copy(colors = doc.colors.copy(pureBlack = pureBlack))
            }
        val g =
            globals.copy(
                enforceContrast = false,
                appearanceOverride = if (variant == Variant.DARK) AppearanceOverride.DARK else AppearanceOverride.LIGHT,
            )
        val r = ThemeResolver.resolveSafely(d, inputs.copy(systemDark = variant == Variant.DARK), g).theme
        return ContrastCaseInput(
            variant,
            pureBlack,
            r.colors,
            WallpaperSpec(r.wallpaper.show, r.wallpaper.dim, r.wallpaper.backgroundOpacity),
        )
    }

    fun report(
        doc: ThemeDocument,
        inputs: PlatformInputs,
        globals: ThemeGlobals = ThemeGlobals(),
    ): ContrastReport {
        val cases = cases(doc, inputs, globals)
        val checks =
            cases.flatMap { c ->
                Contrast.check(c.palette, c.wallpaper).map { PairCheck(c.variant, c.pureBlack, it.pair, it.ratio) }
            }
        return ContrastReport(cases, checks)
    }

    /**
     * "Fix" (§7.5 editor button): runs the enforcement algorithm on each failing case and writes the moved roles into
     * the file's palettes (`colors.light` / `colors.dark`) and any raised `dim` / `backgroundOpacity` into
     * `wallpaper`. Static check (any wallpaper). Returns the new tree, or [tree] when nothing failed. The caller
     * records the result as one undo step.
     */
    fun fix(
        tree: JsonObject,
        parse: (JsonObject) -> ThemeDocument?,
        inputs: PlatformInputs,
        globals: ThemeGlobals = ThemeGlobals(),
    ): JsonObject {
        var t = tree
        repeat(MAX_ROUNDS) {
            val start = parse(t) ?: return t
            val caseCount = cases(start, inputs, globals).size
            if (report(start, inputs, globals).allPass) return t
            for (i in 0 until caseCount) {
                // Re-evaluated per case: an earlier case may have moved shared roles or raised the shared dim.
                val doc = parse(t) ?: return t
                val c = cases(doc, inputs, globals).getOrNull(i) ?: continue
                if (Contrast.passes(c.palette, c.wallpaper)) continue
                t = writeBack(t, c, ContrastEnforcer.enforce(c.palette, c.wallpaper, null, lockedFor(c)))
            }
        }
        return t
    }

    private fun lockedFor(c: ContrastCaseInput): Set<ColorRole> =
        if (c.pureBlack) setOf(ColorRole.BACKGROUND, ColorRole.SCRIM) else emptySet()

    private fun writeBack(
        tree: JsonObject,
        c: ContrastCaseInput,
        out: app.strata.launcher.core.theme.contrast.Enforced,
    ): JsonObject {
        var t = tree
        val locked = lockedFor(c)
        for (role in ColorRole.entries) {
            if (role in locked || out.palette[role] == c.palette[role]) continue
            t =
                TreeEdits.set(
                    t,
                    FieldPath("colors", c.variant.wire, role.wire),
                    JsonString(Rgb.toHex(out.palette[role])),
                )
        }
        if (out.wallpaper.dim != c.wallpaper.dim) {
            t = TreeEdits.set(t, FieldPath("wallpaper", "dim"), JsonNumber.of(up(out.wallpaper.dim, 0.95)))
        }
        if (out.wallpaper.backgroundOpacity != c.wallpaper.backgroundOpacity) {
            t =
                TreeEdits.set(
                    t,
                    FieldPath("wallpaper", "backgroundOpacity"),
                    JsonNumber.of(up(out.wallpaper.backgroundOpacity, 1.0)),
                )
        }
        return t
    }

    /** Rounds up to 2 decimals (rounding down could undo the fix), capped at the field's maximum. */
    private fun up(
        v: Double,
        max: Double,
    ): Double = BigDecimal(v.toString()).setScale(2, RoundingMode.CEILING).toDouble().coerceAtMost(max)

    private const val MAX_ROUNDS = 8
}
