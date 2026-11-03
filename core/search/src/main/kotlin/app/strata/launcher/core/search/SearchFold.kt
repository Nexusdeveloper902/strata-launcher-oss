package app.strata.launcher.core.search

import java.text.Normalizer

/**
 * Normalisation `fold(s)` of ARCHITECTURE §8 (FR-SEARCH-02, NFR-I18N-03), steps 1–3. Deterministic and
 * locale-independent; the same function folds queries and labels. Step 4 (transliteration) is platform
 * work behind `TextFolding` and is applied by [SearchIndex] at build time.
 */
object SearchFold {
    /**
     * 1. NFKD, then drop combining marks (`\p{Mn}`): "Café" → "cafe".
     * 2. Locale-independent lower case, plus ß→ss, ı/İ→i, ø→o, æ→ae, đ→d, ł→l.
     * 3. Whitespace and `-_.·'’` collapse to single spaces (trimmed). Digits and other symbols are kept.
     */
    fun fold(s: String): String {
        if (s.isEmpty()) return ""
        val nfkd = Normalizer.normalize(s, Normalizer.Form.NFKD)
        val out = StringBuilder(nfkd.length)
        var pendingSpace = false
        var i = 0
        while (i < nfkd.length) {
            val cp = nfkd.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) continue
            if (isSeparator(cp)) {
                pendingSpace = out.isNotEmpty()
                continue
            }
            if (pendingSpace) {
                out.append(' ')
                pendingSpace = false
            }
            appendFolded(out, Character.toLowerCase(cp))
        }
        return out.toString()
    }

    /** True when [s] (already folded) contains a letter outside ASCII a–z, i.e. a non-Latin label. */
    fun needsTransliteration(folded: String): Boolean {
        var i = 0
        while (i < folded.length) {
            val cp = folded.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isLetter(cp) && cp !in 'a'.code..'z'.code) return true
        }
        return false
    }

    private fun isSeparator(cp: Int): Boolean =
        Character.isWhitespace(cp) ||
            Character.isSpaceChar(cp) ||
            cp == '-'.code ||
            cp == '_'.code ||
            cp == '.'.code ||
            cp == '·'.code ||
            cp == '\''.code ||
            cp == '’'.code

    private fun appendFolded(
        out: StringBuilder,
        cp: Int,
    ) {
        when (cp) {
            'ß'.code -> out.append("ss")
            'ı'.code -> out.append('i')
            'ø'.code -> out.append('o')
            'æ'.code -> out.append("ae")
            'đ'.code -> out.append('d')
            'ł'.code -> out.append('l')
            else -> out.appendCodePoint(cp)
        }
    }
}
