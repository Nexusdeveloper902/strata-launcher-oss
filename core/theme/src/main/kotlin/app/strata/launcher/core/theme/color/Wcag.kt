package app.strata.launcher.core.theme.color

import kotlin.math.max
import kotlin.math.min

/** WCAG 2.x relative luminance and contrast ratio (THEME_SYSTEM §7.2). */
object Wcag {
    fun luminance(c: RgbF): Double =
        0.2126 * Rgb.linearize(c.r) + 0.7152 * Rgb.linearize(c.g) + 0.0722 * Rgb.linearize(c.b)

    fun luminance(argb: Int): Double = luminance(RgbF.of(argb))

    fun ratio(
        l1: Double,
        l2: Double,
    ): Double = (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)

    fun contrast(
        a: Int,
        b: Int,
    ): Double = ratio(luminance(a), luminance(b))

    /** Source-over in gamma-encoded sRGB, as Android/Compose blend by default (§7.2). */
    fun over(
        top: RgbF,
        alpha: Double,
        bottom: RgbF,
    ): RgbF =
        RgbF(
            alpha * top.r + (1 - alpha) * bottom.r,
            alpha * top.g + (1 - alpha) * bottom.g,
            alpha * top.b + (1 - alpha) * bottom.b,
        )

    /**
     * Worst-case ratio of a foreground of luminance [fg] against a background whose luminance can be
     * anywhere in [[lo], [hi]] (§7.3): 1.0 when [fg] lies inside the interval.
     */
    fun worstRatio(
        fg: Double,
        lo: Double,
        hi: Double,
    ): Double = if (fg in lo..hi) 1.0 else min(ratio(fg, lo), ratio(fg, hi))
}
