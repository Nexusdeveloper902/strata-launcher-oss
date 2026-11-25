package app.strata.launcher.core.search

import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.VisibleApp

/** Score tiers of ARCHITECTURE §8 (higher wins). Transliterated matches score one tier lower. */
enum class MatchTier(val base: Int) {
    EXACT(600),
    PREFIX(500),
    WORD_PREFIX(400),
    INITIALS(300),

    /**
     * A word start typed with neighbouring keys ("pg" → "Phone", FB-015, D-016): below the intentional tiers T1–T4,
     * above substring, package and generic fuzzy matches.
     */
    ADJACENT(250),
    SUBSTRING(200),

    /** A package-name match (D-016): below every label match, above fuzzy. */
    PACKAGE(150),
    FUZZY(100),
}

/** One ranked result. [tier] is the best tier over all label forms. */
data class SearchHit(
    val app: VisibleApp,
    val score: Int,
    val tier: MatchTier,
    /** The best match was on the display (custom) label. */
    val onDisplayLabel: Boolean,
    /** The best match was on a transliterated form (API 29+ only). */
    val viaTransliteration: Boolean,
)

/** Turns a label into Latin (ICU "Any-Latin; Latin-ASCII"), or null when unavailable (API < 29). */
fun interface Transliterate {
    fun transliterate(label: String): String?
}

/**
 * The in-memory app search index (ARCHITECTURE §8). Built off main from the visible app model on every
 * catalog/override/usage change; [rank] is a linear scan over precomputed folded forms (labels, camel-case splits,
 * initials, package names) that allocates no label copies per keystroke (NFR-PERF-03). Immutable and thread-safe.
 */
class SearchIndex private constructor(
    private val entries: Array<Entry>,
    private val keyboard: KeyboardLayout,
    /** The longest label form in code points: no adjacent or fuzzy match is possible beyond it (R-002). */
    private val maxFormLength: Int,
) {
    val size: Int get() = entries.size

    /**
     * Ranks [query] (FR-SEARCH-02). Hidden apps are included only when [includeHidden]
     * (FR-SEARCH-03). Order: tier (one lower for a transliterated form), then score, then collation of the display
     * label, so usage and bonuses never move a result across tiers.
     *
     * Two passes: tiers T1–T5 and the package tier over every entry, then the forgiving tiers over the entries that
     * did not match, only when the first pass found fewer than [FUZZY_WHEN_FEWER_THAN] label matches (package-only hits
     * do not count) and the query contains a letter (FR-SEARCH-02): [MatchTier.ADJACENT] (neighbouring keys at a word
     * start, 2+ characters) first, else T6 fuzzy (4+ characters).
     * Lengths are code points; a query longer than every label form plus the edit budget skips the second pass.
     */
    fun rank(
        query: String,
        includeHidden: Boolean,
    ): List<SearchHit> {
        val q = SearchFold.fold(query)
        if (q.isEmpty()) return emptyList()
        val matches = ArrayList<Match>()
        var labelMatches = 0
        var unmatched: BooleanArray? = null
        for (index in entries.indices) {
            val entry = entries[index]
            if (entry.app.hidden && !includeHidden) continue
            var best: Match? = null
            for (form in entry.forms) {
                val tier = form.match(q) ?: continue
                best = better(best, entry, form, tier)
            }
            if (best != null) {
                matches += best
                if (best.tier != MatchTier.PACKAGE) labelMatches++
            } else {
                if (unmatched == null) unmatched = BooleanArray(entries.size)
                unmatched[index] = true
            }
        }
        if (unmatched != null) forgiving(q, labelMatches, unmatched, matches)
        matches.sortWith(ORDER)
        return matches.map {
            SearchHit(it.entry.app, it.score, it.tier, it.form.display, it.form.transliterated)
        }
    }

    /** The second pass: [MatchTier.ADJACENT], then [MatchTier.FUZZY], over the [unmatched] entries. */
    private fun forgiving(
        q: String,
        labelMatches: Int,
        unmatched: BooleanArray,
        out: MutableList<Match>,
    ) {
        if (labelMatches >= FUZZY_WHEN_FEWER_THAN || !Fuzzy.hasLetter(q)) return
        // Lengths in code points: an emoji or another supplementary character is one character, not two.
        val length = q.codePointCount(0, q.length)
        val edits = Fuzzy.maxEdits(length)
        val slips = Adjacent.maxSlips(length)
        // One key typed says nothing about which key was meant: slips start at two characters (FB-015).
        val adjacent = length in Adjacent.MIN_LENGTH..maxFormLength
        val fuzzy = edits > 0 && length <= maxFormLength + edits
        if (!adjacent && !fuzzy) return
        val cps = q.codePoints().toArray()
        val scratch = if (fuzzy) Fuzzy.Scratch(minOf(length, maxFormLength) + edits + 1) else null
        for (index in entries.indices) {
            if (!unmatched[index]) continue
            val entry = entries[index]
            var best: Match? = null
            if (adjacent) {
                for (form in entry.forms) {
                    val at = form.adjacent(cps, slips, keyboard)
                    if (at != Form.NO_FUZZY) best = better(best, entry, form, MatchTier.ADJACENT, startBonus(at))
                }
            }
            if (best == null && scratch != null) {
                for (form in entry.forms) {
                    val at = form.fuzzy(cps, edits, scratch)
                    if (at != Form.NO_FUZZY) best = better(best, entry, form, MatchTier.FUZZY, startBonus(at))
                }
            }
            if (best != null) out += best
        }
    }

    private fun startBonus(at: Int): Int = if (at == Form.FUZZY_AT_START) START_BONUS else 0

    private fun better(
        current: Match?,
        entry: Entry,
        form: Form,
        tier: MatchTier,
        bonus: Int = 0,
    ): Match? {
        val base = if (form.transliterated) tier.base - TIER_GAP else tier.base
        val score = base + bonus + (if (form.display) DISPLAY_BONUS else 0) + entry.boost
        val better = current == null || base > current.base || (base == current.base && score > current.score)
        return if (better) Match(entry, base, score, tier, form) else current
    }

    /** Apps by key (recents of FR-SEARCH-05 are resolved through the same set). */
    fun appFor(key: AppKey): VisibleApp? = entries.firstOrNull { it.app.key == key }?.app

    /** [base] = the tier's base, one tier lower for a transliterated form: the first sort key. */
    private class Match(val entry: Entry, val base: Int, val score: Int, val tier: MatchTier, val form: Form)

    private class Entry(
        val app: VisibleApp,
        val forms: Array<Form>,
        /** `min(launchCount30d, 50)`; 0 while the log is paused or cleared. */
        val boost: Int,
        /** Position in collation order of the display label (tie-breaker). */
        var collationRank: Int = 0,
    )

    /** How a [Form] was derived, which limits the tiers it can reach. */
    internal enum class FormKind {
        /** The folded label: every tier. */
        LABEL,

        /** The label split at camel-case boundaries ("play tube music"): every tier but fuzzy ("play tub" → PlayTube). */
        CAMEL,

        /**
         * The last two meaningful package segments ("chatty", "stream music"; generic and vendor segments dropped): a
         * word prefix of one of them, or a dotted query inside the whole name ([full]), gives [MatchTier.PACKAGE].
         */
        PACKAGE,
    }

    /** One folded form: display or system label, native or transliterated, plain, camel-split or package. */
    internal class Form(
        val folded: String,
        val display: Boolean,
        val transliterated: Boolean,
        val kind: FormKind = FormKind.LABEL,
        /** [FormKind.PACKAGE] only: the whole folded package name ("com example chatty"). */
        val full: String = folded,
    ) {
        /** Start offsets of words after the first (the first is the label prefix), in UTF-16 units. */
        private val wordStarts: IntArray
        private val initials: String

        /** The folded form as code points, for the adjacent and fuzzy tiers (labels and camel splits; else empty). */
        private val cps: IntArray =
            if (kind == FormKind.PACKAGE) EMPTY_INTS else folded.codePoints().toArray()

        /** Start offsets of words after the first, in code points (indexes into [cps]). */
        private val cpWordStarts: IntArray

        /** Length in code points (0 for package forms, which are never matched forgivingly). */
        val length: Int get() = cps.size

        init {
            val starts = ArrayList<Int>()
            val init = StringBuilder()
            for (i in folded.indices) {
                if (i == 0 || folded[i - 1] == ' ') {
                    if (i > 0) starts += i
                    init.appendCodePoint(folded.codePointAt(i))
                }
            }
            wordStarts = starts.toIntArray()
            initials = init.toString()
            val cpStarts = ArrayList<Int>()
            for (i in 1 until cps.size) if (cps[i - 1] == ' '.code) cpStarts += i
            cpWordStarts = cpStarts.toIntArray()
        }

        fun match(q: String): MatchTier? {
            val f = folded
            if (kind == FormKind.PACKAGE) return matchPackage(f, q)
            if (f.length < q.length) return null
            return matchLabel(f, q)
        }

        private fun matchLabel(
            f: String,
            q: String,
        ): MatchTier? {
            if (f.startsWith(q)) return if (f.length == q.length) MatchTier.EXACT else MatchTier.PREFIX
            if (wordPrefix(q)) return MatchTier.WORD_PREFIX
            if (initialsMatch(q)) return MatchTier.INITIALS
            if (f.contains(q)) return MatchTier.SUBSTRING
            return null
        }

        private fun matchPackage(
            f: String,
            q: String,
        ): MatchTier? {
            if (q.length < PACKAGE_MIN) return null
            val segment = f.length >= q.length && (f.startsWith(q) || wordPrefix(q))
            return if (segment || (q.indexOf(' ') >= 0 && full.contains(q))) MatchTier.PACKAGE else null
        }

        private fun wordPrefix(q: String): Boolean {
            for (start in wordStarts) if (folded.startsWith(q, start)) return true
            return false
        }

        private fun initialsMatch(q: String): Boolean =
            q.length >= 2 && wordStarts.isNotEmpty() && initials.startsWith(q)

        /**
         * [MatchTier.ADJACENT]: [q] equals the start of the label ([FUZZY_AT_START]) or of a later word
         * ([FUZZY_AT_WORD]) except for 1..[slips] substitutions by a neighbouring key of [keyboard]; [NO_FUZZY]
         * otherwise. Labels and camel-case splits, never package names.
         */
        fun adjacent(
            q: IntArray,
            slips: Int,
            keyboard: KeyboardLayout,
        ): Int {
            if (kind == FormKind.PACKAGE || cps.size < q.size) return NO_FUZZY
            if (Adjacent.slipsAt(q, cps, 0, slips, keyboard)) return FUZZY_AT_START
            for (start in cpWordStarts) {
                if (Adjacent.slipsAt(q, cps, start, slips, keyboard)) return FUZZY_AT_WORD
            }
            return NO_FUZZY
        }

        /**
         * T6: [q] (code points) is within [edits] of a prefix of the label ([FUZZY_AT_START]) or of one of its later
         * words ([FUZZY_AT_WORD]); [NO_FUZZY] otherwise. Labels only, never package names or camel splits.
         */
        fun fuzzy(
            q: IntArray,
            edits: Int,
            scratch: Fuzzy.Scratch,
        ): Int {
            if (kind != FormKind.LABEL || cps.size < q.size - edits) return NO_FUZZY
            if (Fuzzy.withinEditsOfPrefix(q, cps, 0, edits, scratch)) return FUZZY_AT_START
            for (start in cpWordStarts) {
                if (cps.size - start < q.size - edits) continue
                if (Fuzzy.withinEditsOfPrefix(q, cps, start, edits, scratch)) return FUZZY_AT_WORD
            }
            return NO_FUZZY
        }

        companion object {
            private val EMPTY_INTS = IntArray(0)
            const val NO_FUZZY = 0
            const val FUZZY_AT_WORD = 1
            const val FUZZY_AT_START = 2
        }
    }

    companion object {
        /** The 100-point gap between tiers; transliterated forms lose one tier. */
        const val TIER_GAP: Int = 100
        const val DISPLAY_BONUS: Int = 20
        const val MAX_BOOST: Int = 50

        /**
         * A fuzzy match at the start of the label ranks above one on a later word ("mpas": Maps before Amazing Maps).
         * 20 + 50 + 10 stays below the 100-point tier gap.
         */
        const val START_BONUS: Int = 10
        const val FUZZY_MIN: Int = 4

        /** T6 runs only when T1–T5 found fewer results than this (FR-SEARCH-02). */
        const val FUZZY_WHEN_FEWER_THAN: Int = 5

        /** A package name is matched only for queries of at least this length. */
        const val PACKAGE_MIN: Int = 3

        val EMPTY: SearchIndex = SearchIndex(emptyArray(), KeyboardLayout.QWERTY, 0)

        /**
         * Package segments that say nothing about the app: domains and platform words ("com", "android"…) and the
         * prefixes of large vendors, which would otherwise match every app they ship ("goo" → all of them).
         */
        private val GENERIC_SEGMENTS: Set<String> =
            setOf(
                "com",
                "org",
                "net",
                "io",
                "de",
                "co",
                "uk",
                "app",
                "apps",
                "android",
                "mobile",
                "client",
                "google",
                "samsung",
                "sec",
                "microsoft",
                "huawei",
                "xiaomi",
                "miui",
                "oneplus",
                "oppo",
                "vivo",
                "motorola",
                "sony",
                "lge",
                "htc",
                "nokia",
                "amazon",
                "meta",
                "facebook",
            )

        /** At most this many meaningful package segments, from the end, are matched. */
        private const val PACKAGE_SEGMENTS = 2

        /** The last [PACKAGE_SEGMENTS] segments of a folded package name that are not generic, joined by spaces. */
        internal fun meaningfulSegments(foldedPackage: String): String {
            val kept = foldedPackage.split(' ').filter { it.isNotEmpty() && it !in GENERIC_SEGMENTS }
            return kept.takeLast(PACKAGE_SEGMENTS).joinToString(" ")
        }

        private val ORDER: Comparator<Match> =
            compareByDescending<Match> { it.base }
                .thenByDescending { it.score }
                .thenBy { it.entry.collationRank }

        /**
         * Builds the index (ARCHITECTURE §8: 500 apps < 5 ms on RD-1, off main). Equal scores are ordered
         * by [comparator] over display labels, or by the order of [apps] when it is null (the assembled
         * list is already in collation order).
         * [launchCounts] are 30-day counts (pass an empty map while the log is paused).
         * [transliterate] is applied to non-Latin labels only; [translitCache] keeps its results across
         * rebuilds so the ICU cost is paid once per label.
         * [keyboard] gives the neighbouring keys of [MatchTier.ADJACENT] (FB-015; QWERTY, the owner's keyboard).
         */
        fun build(
            apps: List<VisibleApp>,
            launchCounts: Map<AppKey, Int>,
            comparator: Comparator<String>?,
            transliterate: Transliterate? = null,
            translitCache: MutableMap<String, String>? = null,
            keyboard: KeyboardLayout = KeyboardLayout.QWERTY,
        ): SearchIndex {
            val entries =
                Array(apps.size) { i ->
                    val app = apps[i]
                    val forms = ArrayList<Form>(4)
                    addForms(forms, app.displayLabel, display = true, transliterate, translitCache)
                    if (app.systemLabel.isNotBlank() && app.systemLabel != app.displayLabel) {
                        addForms(forms, app.systemLabel, display = false, transliterate, translitCache)
                    }
                    val pkg = SearchFold.fold(app.packageName)
                    if (pkg.isNotEmpty()) {
                        forms +=
                            Form(
                                meaningfulSegments(pkg),
                                display = false,
                                transliterated = false,
                                FormKind.PACKAGE,
                                pkg,
                            )
                    }
                    Entry(
                        app = app,
                        forms = forms.toTypedArray(),
                        boost = (launchCounts[app.key] ?: 0).coerceIn(0, MAX_BOOST),
                    )
                }
            if (comparator == null) {
                entries.forEachIndexed { i, e -> e.collationRank = i }
            } else {
                val order =
                    entries.indices.sortedWith { a, b ->
                        val c = comparator.compare(entries[a].app.displayLabel, entries[b].app.displayLabel)
                        if (c != 0) {
                            c
                        } else {
                            entries[a]
                                .app.key.component
                                .compareTo(entries[b].app.key.component)
                        }
                    }
                order.forEachIndexed { rank, i -> entries[i].collationRank = rank }
            }
            var maxLength = 0
            for (e in entries) for (f in e.forms) if (f.length > maxLength) maxLength = f.length
            return SearchIndex(entries, keyboard, maxLength)
        }

        private fun addForms(
            out: MutableList<Form>,
            label: String,
            display: Boolean,
            transliterate: Transliterate?,
            cache: MutableMap<String, String>?,
        ) {
            val folded = SearchFold.fold(label)
            if (folded.isEmpty()) return
            out += Form(folded, display, transliterated = false)
            val split = SearchFold.splitCamel(label)
            if (split !== label) {
                val camel = SearchFold.fold(split)
                if (camel != folded) out += Form(camel, display, transliterated = false, FormKind.CAMEL)
            }
            if (transliterate == null || !SearchFold.needsTransliteration(folded)) return
            val latin =
                cache?.get(label) ?: run {
                    val t = runCatching { transliterate.transliterate(label) }.getOrNull()
                    val f = t?.let(SearchFold::fold).orEmpty()
                    cache?.put(label, f)
                    f
                }
            if (latin.isNotEmpty() && latin != folded) out += Form(latin, display, transliterated = true)
        }
    }
}

/**
 * Tier T6 (ARCHITECTURE §8): optimal-string-alignment (restricted Damerau-Levenshtein) distance between the query and
 * a prefix of a label word. Allowed edits: 1 for queries of 4–7 characters, 2 from 8 (FR-SEARCH-02, Proposed).
 */
internal object Fuzzy {
    /** True when [q] contains a letter: queries of digits, symbols or emoji are never matched fuzzily. */
    fun hasLetter(q: String): Boolean {
        var i = 0
        while (i < q.length) {
            val cp = q.codePointAt(i)
            if (Character.isLetter(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /** Edits allowed for a folded query of [length] characters (code points); 0 = no fuzzy matching. */
    fun maxEdits(length: Int): Int =
        when {
            length < SearchIndex.FUZZY_MIN -> 0
            length < TWO_EDITS_FROM -> 1
            else -> 2
        }

    private const val TWO_EDITS_FROM = 8

    /** Three DP rows, reused across the entries of one [SearchIndex.rank] call. */
    class Scratch(capacity: Int) {
        var prev2 = IntArray(capacity)
        var prev = IntArray(capacity)
        var cur = IntArray(capacity)

        fun ensure(size: Int) {
            if (cur.size >= size) return
            prev2 = IntArray(size)
            prev = IntArray(size)
            cur = IntArray(size)
        }
    }

    /** True when [q] is within one edit of `f[0, L)` for some L in `q.length - 1 .. q.length + 1` (code points). */
    fun withinOneEditOfPrefix(
        q: String,
        f: String,
    ): Boolean {
        val qc = q.codePoints().toArray()
        return withinEditsOfPrefix(qc, f.codePoints().toArray(), 0, 1, Scratch(qc.size + 2))
    }

    /**
     * True when the OSA distance between [q] and `f[start, start + L)` is at most [k] for some prefix length L
     * (`q.size - k ≤ L ≤ q.size + k`). Both are code points, so a surrogate pair is one character (R-002). The prefix
     * may run across word boundaries ("grandmaps" → "grand maps"). [scratch] grows to the label side only.
     */
    fun withinEditsOfPrefix(
        q: IntArray,
        f: IntArray,
        start: Int,
        k: Int,
        scratch: Scratch,
    ): Boolean {
        val n = q.size
        val m = minOf(f.size - start, n + k)
        if (m < n - k || n == 0) return false
        scratch.ensure(m + 1)
        var prev2 = scratch.prev2
        var prev = scratch.prev
        var cur = scratch.cur
        for (j in 0..m) prev[j] = j
        for (i in 1..n) {
            cur[0] = i
            var rowMin = i
            val qc = q[i - 1]
            for (j in 1..m) {
                val fc = f[start + j - 1]
                var d = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (qc == fc) 0 else 1)
                if (i > 1 && j > 1 && qc == f[start + j - 2] && q[i - 2] == fc) d = minOf(d, prev2[j - 2] + 1)
                cur[j] = d
                if (d < rowMin) rowMin = d
            }
            if (rowMin > k) return false
            val t = prev2
            prev2 = prev
            prev = cur
            cur = t
        }
        // `prev` holds row n: the distance from q to each prefix f[start, start + j).
        for (j in maxOf(0, n - k)..m) if (prev[j] <= k) return true
        return false
    }
}

/**
 * [MatchTier.ADJACENT] (FB-015, FR-SEARCH-02 as amended, Proposed): the query is a word start of the label typed with
 * one or more fingers on a neighbouring key: substitutions only, each between neighbouring keys of the layout, no
 * insertions, deletions or other substitutions.
 */
internal object Adjacent {
    /** Neighbour-key substitutions allowed for a query of [length] code points: 1 up to 4, 2 for 5–9, 3 from 10. */
    fun maxSlips(length: Int): Int =
        when {
            length < TWO_FROM -> 1
            length < THREE_FROM -> 2
            else -> 3
        }

    /** A single key never slips. */
    const val MIN_LENGTH = 2
    private const val TWO_FROM = 5
    private const val THREE_FROM = 10

    /** True when `f[start, start + q.size)` differs from [q] in 1..[slips] places, each a neighbouring key. */
    fun slipsAt(
        q: IntArray,
        f: IntArray,
        start: Int,
        slips: Int,
        keyboard: KeyboardLayout,
    ): Boolean {
        if (f.size - start < q.size) return false
        var used = 0
        for (i in q.indices) {
            val a = q[i]
            val b = f[start + i]
            if (a == b) continue
            if (++used > slips || !keyboard.isNeighbour(a, b)) return false
        }
        return used > 0
    }
}
