package app.strata.launcher.core.theme.color

/** All tones of one hue/chroma pair (THEME_SYSTEM §5.2). Tones are cached per instance. */
class TonalPalette(val hue: Double, val chroma: Double) {
    private val cache = HashMap<Int, Int>()

    fun tone(t: Int): Int = synchronized(cache) { cache.getOrPut(t) { HctSolver.solve(hue, chroma, t.toDouble()) } }
}

/**
 * The colour roles of a Material-style scheme that THEME_SYSTEM §5.2 maps onto Strata's nine roles.
 * The platform's dynamic scheme (API 31+) arrives in this shape too.
 */
data class MaterialRoles(
    val surface: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    val onPrimary: Int,
    val outlineVariant: Int,
)

/**
 * "Tonal spot" scheme from one seed colour: primary chroma 36, neutral 6, neutral-variant 8,
 * standard Material tone assignments for the eight roles above.
 */
object TonalSpot {
    fun scheme(
        seed: Int,
        dark: Boolean,
    ): MaterialRoles {
        val hue = Hct.fromArgb(seed).hue
        val primary = TonalPalette(hue, 36.0)
        val neutral = TonalPalette(hue, 6.0)
        val neutralVariant = TonalPalette(hue, 8.0)
        return if (dark) {
            MaterialRoles(
                surface = neutral.tone(6),
                surfaceContainerHigh = neutral.tone(17),
                surfaceContainerHighest = neutral.tone(22),
                onSurface = neutral.tone(90),
                onSurfaceVariant = neutralVariant.tone(80),
                primary = primary.tone(80),
                onPrimary = primary.tone(20),
                outlineVariant = neutralVariant.tone(30),
            )
        } else {
            MaterialRoles(
                surface = neutral.tone(98),
                surfaceContainerHigh = neutral.tone(92),
                surfaceContainerHighest = neutral.tone(90),
                onSurface = neutral.tone(10),
                onSurfaceVariant = neutralVariant.tone(30),
                primary = primary.tone(40),
                onPrimary = primary.tone(100),
                outlineVariant = neutralVariant.tone(80),
            )
        }
    }
}
