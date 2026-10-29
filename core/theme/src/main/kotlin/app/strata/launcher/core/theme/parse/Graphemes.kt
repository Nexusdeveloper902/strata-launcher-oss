package app.strata.launcher.core.theme.parse

/**
 * Grapheme-cluster counting for the `name`/`author`/`description` limits (THEME_SYSTEM §4.1).
 *
 * A deterministic subset of UAX #29 extended grapheme clusters, implemented here so the result
 * does not depend on the JDK or Android ICU version:
 * CR LF; Hangul L/V/T/LV/LVT sequences; Extend (non-spacing, enclosing and spacing combining marks,
 * ZWNJ, variation selectors, emoji modifiers, tag characters) and ZWJ never start a cluster;
 * a pictograph after ZWJ joins the cluster; regional indicators pair up. Prepend characters and
 * Indic conjunct rules are not modelled (they only make the count slightly higher, never lower).
 */
object Graphemes {
    /** Start offsets (UTF-16 indexes) of each cluster in [s]. */
    fun boundaries(s: String): IntArray {
        val out = ArrayList<Int>()
        var i = 0
        var prev = -1
        var prevWasZwj = false
        var riRun = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val joins =
                prev >= 0 &&
                    when {
                        prev == CR && cp == LF -> true
                        prev == CR || prev == LF || isControl(prev) -> false
                        cp == CR || cp == LF || isControl(cp) -> false
                        isExtend(cp) || cp == ZWJ -> true
                        prevWasZwj && isPictographic(cp) -> true
                        isRegionalIndicator(prev) && isRegionalIndicator(cp) -> riRun % 2 == 1
                        hangulJoins(prev, cp) -> true
                        else -> false
                    }
            if (!joins) out += i
            riRun = if (isRegionalIndicator(cp)) (if (joins) riRun + 1 else 1) else 0
            prevWasZwj = cp == ZWJ
            prev = cp
            i += Character.charCount(cp)
        }
        return out.toIntArray()
    }

    fun count(s: String): Int = boundaries(s).size

    /** The first [max] clusters of [s]. */
    fun truncate(
        s: String,
        max: Int,
    ): String {
        val b = boundaries(s)
        return if (b.size <= max) s else s.substring(0, b[max])
    }

    private const val CR = 0x0D
    private const val LF = 0x0A
    private const val ZWJ = 0x200D

    private fun isControl(cp: Int): Boolean {
        val t = Character.getType(cp)
        return (
            t == Character.CONTROL.toInt() || t == Character.LINE_SEPARATOR.toInt() ||
                t == Character.PARAGRAPH_SEPARATOR.toInt()
        ) ||
            (t == Character.FORMAT.toInt() && cp != ZWJ && cp != 0x200C && cp !in 0xE0020..0xE007F)
    }

    private fun isExtend(cp: Int): Boolean {
        val t = Character.getType(cp)
        return t == Character.NON_SPACING_MARK.toInt() ||
            t == Character.ENCLOSING_MARK.toInt() ||
            t == Character.COMBINING_SPACING_MARK.toInt() ||
            cp == 0x200C ||
            cp in 0xFE00..0xFE0F ||
            cp in 0xE0100..0xE01EF ||
            cp in 0x1F3FB..0x1F3FF ||
            cp in 0xE0020..0xE007F
    }

    private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

    private fun isPictographic(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2300..0x23FF || cp in 0x2B00..0x2BFF ||
            cp == 0x00A9 || cp == 0x00AE || cp in 0x2190..0x21FF || cp in 0x1FC00..0x1FFFD

    private fun hangulJoins(
        a: Int,
        b: Int,
    ): Boolean {
        val ta = hangulType(a)
        val tb = hangulType(b)
        return when (ta) {
            'L' -> tb == 'L' || tb == 'V' || tb == 'A' || tb == 'B'

            // A = LV, B = LVT
            'V', 'A' -> tb == 'V' || tb == 'T'

            'T', 'B' -> tb == 'T'

            else -> false
        }
    }

    private fun hangulType(cp: Int): Char =
        when {
            cp in 0x1100..0x115F || cp in 0xA960..0xA97C -> 'L'
            cp in 0x1160..0x11A7 || cp in 0xD7B0..0xD7C6 -> 'V'
            cp in 0x11A8..0x11FF || cp in 0xD7CB..0xD7FB -> 'T'
            cp in 0xAC00..0xD7A3 -> if ((cp - 0xAC00) % 28 == 0) 'A' else 'B'
            else -> ' '
        }
}
