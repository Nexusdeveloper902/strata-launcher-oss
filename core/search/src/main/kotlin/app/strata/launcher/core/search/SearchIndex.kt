package app.strata.launcher.core.search

import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.VisibleApp

/** Score tiers of ARCHITECTURE §8 (higher wins). Transliterated matches score one tier lower. */
enum class MatchTier(val base: Int) {
    EXACT(600),
    PREFIX(500),
    WORD_PREFIX(400),
    INITIALS(300),
    SUBSTRING(200),
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
 * catalog/override/usage change; [rank] is a linear scan over precomputed folded forms that allocates
 * no label copies per keystroke (NFR-PERF-03). Immutable and thread-safe.
 */
class SearchIndex private constructor(
    private val entries: Array<Entry>,
) {
    val size: Int get() = entries.size

    /**
     * Ranks [query] (FR-SEARCH-02). Hidden apps are included only when [includeHidden]
     * (FR-SEARCH-03). Order: score desc, then collation of the display label.
     */
    fun rank(
        query: String,
        includeHidden: Boolean,
    ): List<SearchHit> {
        val q = SearchFold.fold(query)
        if (q.isEmpty()) return emptyList()
        val matches = ArrayList<Match>()
        for (entry in entries) {
            if (entry.app.hidden && !includeHidden) continue
            var best: Match? = null
            for (form in entry.forms) {
                val tier = form.match(q) ?: continue
                val base = if (form.transliterated) tier.base - TIER_GAP else tier.base
                val score = base + (if (form.display) DISPLAY_BONUS else 0) + entry.boost
                if (best == null || score > best.score) best = Match(entry, score, tier, form)
            }
            if (best != null) matches += best
        }
        matches.sortWith(ORDER)
        return matches.map {
            SearchHit(it.entry.app, it.score, it.tier, it.form.display, it.form.transliterated)
        }
    }

    /** Apps by key (recents of FR-SEARCH-05 are resolved through the same set). */
    fun appFor(key: AppKey): VisibleApp? = entries.firstOrNull { it.app.key == key }?.app

    private class Match(val entry: Entry, val score: Int, val tier: MatchTier, val form: Form)

    private class Entry(
        val app: VisibleApp,
        val forms: Array<Form>,
        /** `min(launchCount30d, 50)`; 0 while the log is paused or cleared. */
        val boost: Int,
        /** Position in collation order of the display label (tie-breaker). */
        var collationRank: Int = 0,
    )

    /** One folded label form: display or system, native or transliterated. */
    internal class Form(
        val folded: String,
        val display: Boolean,
        val transliterated: Boolean,
    ) {
        /** Start offsets of words after the first (the first is the label prefix). */
        private val wordStarts: IntArray
        private val initials: String

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
        }

        fun match(q: String): MatchTier? {
            val f = folded
            if (f.length < q.length - 1) return null
            if (f.startsWith(q)) return if (f.length == q.length) MatchTier.EXACT else MatchTier.PREFIX
            for (start in wordStarts) if (f.startsWith(q, start)) return MatchTier.WORD_PREFIX
            if (q.length >= 2 && wordStarts.isNotEmpty() && initials.startsWith(q)) return MatchTier.INITIALS
            if (f.contains(q)) return MatchTier.SUBSTRING
            if (q.length >= FUZZY_MIN && Fuzzy.withinOneEditOfPrefix(q, f)) return MatchTier.FUZZY
            return null
        }
    }

    companion object {
        /** The 100-point gap between tiers; transliterated forms lose one tier. */
        const val TIER_GAP: Int = 100
        const val DISPLAY_BONUS: Int = 20
        const val MAX_BOOST: Int = 50
        const val FUZZY_MIN: Int = 4

        val EMPTY: SearchIndex = SearchIndex(emptyArray())

        private val ORDER: Comparator<Match> =
            compareByDescending<Match> { it.score }
                .thenBy { it.entry.collationRank }

        /**
         * Builds the index (ARCHITECTURE §8: 500 apps < 5 ms on RD-1, off main). Equal scores are ordered
         * by [comparator] over display labels, or by the order of [apps] when it is null (the assembled
         * list is already in collation order).
         * [launchCounts] are 30-day counts (pass an empty map while the log is paused).
         * [transliterate] is applied to non-Latin labels only; [translitCache] keeps its results across
         * rebuilds so the ICU cost is paid once per label.
         */
        fun build(
            apps: List<VisibleApp>,
            launchCounts: Map<AppKey, Int>,
            comparator: Comparator<String>?,
            transliterate: Transliterate? = null,
            translitCache: MutableMap<String, String>? = null,
        ): SearchIndex {
            val entries =
                Array(apps.size) { i ->
                    val app = apps[i]
                    val forms = ArrayList<Form>(2)
                    addForms(forms, app.displayLabel, display = true, transliterate, translitCache)
                    if (app.systemLabel.isNotBlank() && app.systemLabel != app.displayLabel) {
                        addForms(forms, app.systemLabel, display = false, transliterate, translitCache)
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
            return SearchIndex(entries)
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

/** Optimal-string-alignment distance ≤ 1 against a label prefix (ARCHITECTURE §8 tier T6). */
internal object Fuzzy {
    /** True when [q] is within one edit of `f[0, L)` for some L in `q.length - 1 .. q.length + 1`. */
    fun withinOneEditOfPrefix(
        q: String,
        f: String,
    ): Boolean {
        val n = q.length
        if (n <= f.length && sameLengthWithinOne(q, f, n)) return true
        if (n - 1 >= 1 && n - 1 <= f.length && oneDeletion(q, n, f, n - 1)) return true
        if (n + 1 <= f.length && oneDeletion(f, n + 1, q, n)) return true
        return false
    }

    /** At most one substitution or one adjacent transposition between `q` and `f[0, n)`. */
    private fun sameLengthWithinOne(
        q: String,
        f: String,
        n: Int,
    ): Boolean {
        var i = 0
        while (i < n && q[i] == f[i]) i++
        if (i == n) return true
        // Substitution at i?
        if (q.regionMatches(i + 1, f, i + 1, n - i - 1)) return true
        // Transposition of i and i+1?
        return i + 1 < n && q[i] == f[i + 1] && q[i + 1] == f[i] && q.regionMatches(i + 2, f, i + 2, n - i - 2)
    }

    /** `longer[0, ln)` becomes `shorter[0, sn)` by deleting exactly one char (ln = sn + 1). */
    private fun oneDeletion(
        longer: String,
        ln: Int,
        shorter: String,
        sn: Int,
    ): Boolean {
        var i = 0
        while (i < sn && longer[i] == shorter[i]) i++
        return longer.regionMatches(i + 1, shorter, i, sn - i) && ln == sn + 1
    }
}
