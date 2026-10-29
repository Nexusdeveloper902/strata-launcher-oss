package app.strata.launcher.core.theme.contrast

import app.strata.launcher.core.theme.color.Hct
import app.strata.launcher.core.theme.color.HctSolver
import app.strata.launcher.core.theme.color.Wcag
import app.strata.launcher.core.theme.model.ColorRole
import app.strata.launcher.core.theme.model.Palette
import app.strata.launcher.core.theme.model.WallpaperSpec
import kotlin.math.min
import kotlin.math.roundToInt

/** Output of [ContrastEnforcer.enforce]. */
data class Enforced(val palette: Palette, val wallpaper: WallpaperSpec) {
    fun changedFrom(
        p: Palette,
        w: WallpaperSpec,
    ): Boolean = p != palette || w != wallpaper
}

/**
 * Render-time contrast enforcement (THEME_SYSTEM §7.5). Pure; the user's file is never touched.
 *
 * For each failing pair, in the order P1, P2, P4, P5, P7, P3, P6, then P8:
 * 1. move the foreground's HCT tone (hue and chroma kept) away from the background, by bisection, until
 *    the ratio is ≥ target + 0.1;
 * 2. if tone 0/100 is not enough and the pair is against the effective background with `show: true`,
 *    raise `dim` in 0.05 steps up to 0.95, then `backgroundOpacity` in 0.1 steps up to 1.0;
 * 3. otherwise move the background role's tone the opposite way;
 * 4. P8 last: move `onAccent` towards black or white, whichever reaches the target first.
 * Roles in [locked] are never changed (locked roles of derived palettes, pure-black roles); a pair whose
 * adjustable roles cannot reach the target is left as close as possible. Passes repeat (≤ 4) because a
 * later fix can move a colour an earlier pair depends on.
 */
object ContrastEnforcer {
    val ORDER =
        listOf(
            ContrastPair.P1,
            ContrastPair.P2,
            ContrastPair.P4,
            ContrastPair.P5,
            ContrastPair.P7,
            ContrastPair.P3,
            ContrastPair.P6,
            ContrastPair.P8,
        )
    private const val MARGIN = 0.1
    private const val BISECT_STEPS = 18

    fun enforce(
        palette: Palette,
        wallpaper: WallpaperSpec,
        wallpaperColors: List<Int>? = null,
        locked: Set<ColorRole> = emptySet(),
    ): Enforced {
        var p = palette
        var w = wallpaper
        repeat(4) {
            var changed = false
            for (pair in ORDER) {
                if (Contrast.ratio(pair, p, w, wallpaperColors) >= pair.minimum) continue
                val (np, nw) = fix(pair, p, w, wallpaperColors, locked)
                if (np != p || nw != w) changed = true
                p = np
                w = nw
            }
            if (!changed) return Enforced(p, w)
        }
        return Enforced(p, w)
    }

    private fun fix(
        pair: ContrastPair,
        p0: Palette,
        w0: WallpaperSpec,
        wc: List<Int>?,
        locked: Set<ColorRole>,
    ): Pair<Palette, WallpaperSpec> {
        val goal = pair.minimum + MARGIN

        fun ratio(
            p: Palette,
            w: WallpaperSpec,
        ) = Contrast.ratio(pair, p, w, wc)

        if (pair == ContrastPair.P8) {
            if (ColorRole.ON_ACCENT in locked) return p0 to w0
            val best =
                listOf(0.0, 100.0)
                    .mapNotNull { t ->
                        moveTone(p0.onAccent, t) { c ->
                            ratio(p0.with(ColorRole.ON_ACCENT, c), w0) >=
                                goal
                        }
                    }.minByOrNull { toneDistance(it, p0.onAccent) }
                    ?: listOf(
                        0.0,
                        100.0,
                    ).map { HctSolver.solve(0.0, 0.0, it) }.maxBy { ratio(p0.with(ColorRole.ON_ACCENT, it), w0) }
            return p0.with(ColorRole.ON_ACCENT, best) to w0
        }

        var p = p0
        var w = w0
        val fgRole = pair.fg
        val bgRole = pair.bg ?: ColorRole.BACKGROUND
        val towardsTone = if (fgIsLighter(pair, p, w, wc)) 100.0 else 0.0

        // Step 1: foreground tone.
        if (fgRole !in locked) {
            val moved = moveTone(p[fgRole], towardsTone) { c -> ratio(p.with(fgRole, c), w) >= goal }
            if (moved != null) return p.with(fgRole, moved) to w
            val extreme = HctSolver.solve(0.0, 0.0, towardsTone)
            if (ratio(p.with(fgRole, extreme), w) > ratio(p, w)) p = p.with(fgRole, extreme)
        }

        // Step 2: wallpaper dimming, only against the effective background of a shown wallpaper.
        if (pair.bg == null && w.show) {
            while (ratio(p, w) < goal && w.dim < 0.95 - 1e-9) w = w.copy(dim = min(0.95, round2(w.dim + 0.05)))
            while (ratio(p, w) < goal && w.backgroundOpacity < 1.0 - 1e-9) {
                w = w.copy(backgroundOpacity = min(1.0, round2(w.backgroundOpacity + 0.1)))
            }
            if (ratio(p, w) >= goal) {
                // Re-fit the foreground from its original colour: the smaller change is enough now.
                if (fgRole !in locked) {
                    moveTone(p0[fgRole], towardsTone) { c -> ratio(p.with(fgRole, c), w) >= goal }?.let {
                        p =
                            p.with(fgRole, it)
                    }
                }
                return p to w
            }
        }

        // Step 3: background role, opposite direction.
        if (ratio(p, w) < goal && bgRole !in locked && !(pair.bg == null && w.show && w.backgroundOpacity < 1.0)) {
            val awayTone = 100.0 - towardsTone
            moveTone(p[bgRole], awayTone) { c -> ratio(p.with(bgRole, c), w) >= goal }?.let {
                return p.with(bgRole, it) to
                    w
            }
            val extreme = HctSolver.solve(0.0, 0.0, awayTone)
            if (ratio(p.with(bgRole, extreme), w) > ratio(p, w)) p = p.with(bgRole, extreme)
        }
        return p to w
    }

    /** Whether the foreground should get lighter: it is lighter than the (middle of the) background now. */
    private fun fgIsLighter(
        pair: ContrastPair,
        p: Palette,
        w: WallpaperSpec,
        wc: List<Int>?,
    ): Boolean {
        val fl = Wcag.luminance(p[pair.fg])
        val bl =
            when {
                pair.bg != null -> {
                    Wcag.luminance(p[pair.bg])
                }

                !w.show -> {
                    Wcag.luminance(p.background)
                }

                wc.isNullOrEmpty() -> {
                    Contrast.effectiveRange(p, w).let { (it.start + it.endInclusive) / 2 }
                }

                else -> {
                    wc
                        .map {
                            Wcag.luminance(
                                Contrast.effectiveBackground(
                                    p,
                                    w,
                                    app.strata.launcher.core.theme.color.RgbF
                                        .of(it),
                                ),
                            )
                        }.let { (it.min() + it.max()) / 2 }
                }
            }
        if (fl != bl) return fl > bl
        // Equal: go where there is more room.
        return Wcag.ratio(1.0, bl) > Wcag.ratio(0.0, bl)
    }

    /**
     * Smallest tone move of [argb] towards [targetTone] (0 or 100) that satisfies [ok], or null if even
     * the extreme does not. Hue and chroma are kept (HCT re-fits chroma into the gamut where needed).
     */
    internal fun moveTone(
        argb: Int,
        targetTone: Double,
        ok: (Int) -> Boolean,
    ): Int? {
        val hct = Hct.fromArgb(argb)
        val extreme = HctSolver.solve(hct.hue, hct.chroma, targetTone)
        if (!ok(extreme)) return null
        var bad = hct.tone
        var good = targetTone
        var best = extreme
        repeat(BISECT_STEPS) {
            val mid = (bad + good) / 2
            val c = HctSolver.solve(hct.hue, hct.chroma, mid)
            if (ok(c)) {
                good = mid
                best = c
            } else {
                bad = mid
            }
        }
        return best
    }

    private fun toneDistance(
        a: Int,
        b: Int,
    ): Double = kotlin.math.abs(Hct.fromArgb(a).tone - Hct.fromArgb(b).tone)

    private fun round2(v: Double): Double = (v * 100).roundToInt() / 100.0
}
