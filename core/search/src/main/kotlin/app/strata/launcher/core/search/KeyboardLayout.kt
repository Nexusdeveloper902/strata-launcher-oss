package app.strata.launcher.core.search

import kotlin.math.abs

/**
 * Which letter keys sit next to each other on the user's keyboard (FR-SEARCH-02 as amended by FB-015, D-016; Proposed).
 * Search treats a substitution between neighbouring keys as a slip of the finger, cheaper than any other typo.
 *
 * The neighbours are derived from geometry, once per layout: [rows] of letters, each shifted right by its entry in
 * [offsets] (in key widths); two keys are neighbours when they are side by side in a row, or in adjacent rows with
 * centres less than one key width apart (each key touches two keys above and two below). Letters are folded a–z only;
 * every other code point has no neighbours.
 *
 * This is a seam: the index takes any layout ([SearchIndex.build]), the app passes [QWERTY] (the owner's keyboard).
 * [QWERTZ] and [AZERTY] are ready for a later setting or IME-subtype lookup (Proposed, not wired).
 */
class KeyboardLayout private constructor(
    /** Bit `b - 'a'` of `masks[a - 'a']` is set when keys a and b are neighbours. */
    private val masks: IntArray,
) {
    /** True when the folded code points [a] and [b] are different, neighbouring letter keys. */
    fun isNeighbour(
        a: Int,
        b: Int,
    ): Boolean {
        val i = a - 'a'.code
        val j = b - 'a'.code
        if (i !in 0 until LETTERS || j !in 0 until LETTERS) return false
        return masks[i] and (1 shl j) != 0
    }

    /** The neighbours of letter [c], in alphabetical order (tests and documentation). */
    fun neighboursOf(c: Char): String {
        val i = c - 'a'
        if (i !in 0 until LETTERS) return ""
        return buildString { for (j in 0 until LETTERS) if (masks[i] and (1 shl j) != 0) append('a' + j) }
    }

    companion object {
        private const val LETTERS = 26

        /** Row shifts of a phone keyboard's letter rows, in key widths (the usual staggered geometry). */
        private val STAGGER = listOf(0.0, 0.25, 0.75)

        /** Builds a layout from its letter [rows] (top first) and their right shifts in key widths. */
        fun of(
            rows: List<String>,
            offsets: List<Double> = STAGGER,
        ): KeyboardLayout {
            require(rows.size <= offsets.size) { "one offset per row" }
            val masks = IntArray(LETTERS)
            for ((r, row) in rows.withIndex()) {
                for ((i, a) in row.withIndex()) {
                    val centre = offsets[r] + i
                    for (r2 in maxOf(0, r - 1)..minOf(rows.size - 1, r + 1)) {
                        for ((j, b) in rows[r2].withIndex()) {
                            if (a == b) continue
                            val d = abs(offsets[r2] + j - centre)
                            val touching = if (r2 == r) d <= 1.0 else d < 1.0
                            if (touching) link(masks, a, b)
                        }
                    }
                }
            }
            return KeyboardLayout(masks)
        }

        private fun link(
            masks: IntArray,
            a: Char,
            b: Char,
        ) {
            val i = a - 'a'
            val j = b - 'a'
            if (i !in 0 until LETTERS || j !in 0 until LETTERS) return
            masks[i] = masks[i] or (1 shl j)
            masks[j] = masks[j] or (1 shl i)
        }

        /** The owner's keyboard (D-011: Galaxy A56). */
        val QWERTY: KeyboardLayout = of(listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"))

        /** German, Austrian, Swiss: Y and Z swapped. */
        val QWERTZ: KeyboardLayout = of(listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"))

        /** French: A/Q and Z/W swapped, M at the end of the middle row. */
        val AZERTY: KeyboardLayout = of(listOf("azertyuiop", "qsdfghjklm", "wxcvbn"))

        /** No neighbours: adjacency matching off. */
        val NONE: KeyboardLayout = KeyboardLayout(IntArray(LETTERS))
    }
}
