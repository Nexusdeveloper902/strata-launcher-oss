package app.strata.launcher.core.theme.color

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Opaque sRGB colour helpers on packed `0xFFRRGGBB` ints. Theme colours never carry alpha
 * (THEME_SYSTEM §4.2), so the alpha byte is always 0xFF.
 */
object Rgb {
    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

    fun isHex(s: String): Boolean = HEX.matches(s)

    /** `#RRGGBB` (case-insensitive) → `0xFFRRGGBB`, or null when malformed. */
    fun parseHex(s: String): Int? = if (isHex(s)) (0xFF000000.toInt() or s.substring(1).toInt(16)) else null

    /** Upper-case `#RRGGBB`. */
    fun toHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    fun of(
        r: Int,
        g: Int,
        b: Int,
    ): Int = (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun red(c: Int): Int = (c shr 16) and 0xFF

    fun green(c: Int): Int = (c shr 8) and 0xFF

    fun blue(c: Int): Int = c and 0xFF

    const val BLACK: Int = 0xFF000000.toInt()
    const val WHITE: Int = 0xFFFFFFFF.toInt()

    /** sRGB channel (0..1, gamma-encoded) → linear, WCAG 2.x formula (0.04045 threshold). */
    fun linearize(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    /** Linear (0..1) → gamma-encoded sRGB (0..1). */
    fun delinearize(c: Double): Double = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055

    fun channelToUnit(c: Int): Double = c / 255.0

    fun unitToChannel(c: Double): Int = (c * 255.0).roundToInt().coerceIn(0, 255)
}

/** A colour with fractional channels (0..1, gamma-encoded sRGB), used for compositing (§7.2). */
data class RgbF(val r: Double, val g: Double, val b: Double) {
    fun toArgb(): Int = Rgb.of(Rgb.unitToChannel(r), Rgb.unitToChannel(g), Rgb.unitToChannel(b))

    companion object {
        fun of(argb: Int): RgbF =
            RgbF(
                Rgb.channelToUnit(Rgb.red(argb)),
                Rgb.channelToUnit(Rgb.green(argb)),
                Rgb.channelToUnit(Rgb.blue(argb)),
            )

        val BLACK = RgbF(0.0, 0.0, 0.0)
        val WHITE = RgbF(1.0, 1.0, 1.0)
    }
}
