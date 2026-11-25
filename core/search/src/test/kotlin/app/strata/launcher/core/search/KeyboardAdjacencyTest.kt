package app.strata.launcher.core.search

import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.testing.fixtures.SearchFixtures.visible
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * T-SEARCH-28…31 (Proposed, FB-015, D-016): neighbouring-key slips ("pg" for "ph" when looking for Phone) and R-002
 * (fuzzy distance over code points, no fuzzy pass for queries longer than every label). The app list is invented and
 * shaped like a real phone's: generic system apps plus made-up names; packages are `com.example.*`.
 */
class KeyboardAdjacencyTest {
    private val phoneLike =
        listOf(
            "Phone",
            "Photos",
            "Pages",
            "Messages",
            "Contacts",
            "Camera",
            "Calendar",
            "Calculator",
            "Clock",
            "Settings",
            "Gallery",
            "Files",
            "Music",
            "Notes",
            "Weather",
            "Maps",
            "Mail",
            "Browser",
            "Wallet",
            "Health",
            "Recorder",
            "Radio",
            "Translate",
            "Podcasts",
            "Cards Vault",
            "Pixelgram",
            "ChattyApp",
            "Soundify",
            "Grand Maps",
            "Tidepool Bank",
            "Hopscotch",
            "Ledgerly",
            "Quillnote",
            "Voltride",
            "Brightside News",
            "Fernway Travel",
            "Kitebox",
            "Graphite Sketch",
            "Yarnball Games",
            "Lingofern",
            "Shopping List",
        ).map { visible(it, pkg = "com.example." + it.lowercase().replace(" ", "")) }
    private val idx = index(phoneLike)

    private fun app(label: String): VisibleApp = phoneLike.first { it.displayLabel == label }

    private fun SearchIndex.tiers(q: String): List<Pair<String, MatchTier>> =
        rank(q, includeHidden = true).map { it.app.displayLabel to it.tier }

    /** T-SEARCH-28: the QWERTY neighbour map, and the layout seam (QWERTZ, AZERTY, none). */
    @Test
    fun tSearch28_neighbourMap() {
        val qwerty = KeyboardLayout.QWERTY
        assertThat(qwerty.neighboursOf('g')).isEqualTo("bfhtvy")
        assertThat(qwerty.neighboursOf('h')).isEqualTo("bgjnuy")
        assertThat(qwerty.neighboursOf('p')).isEqualTo("lo")
        assertThat(qwerty.neighboursOf('a')).isEqualTo("qswz")
        assertThat(qwerty.neighboursOf('m')).isEqualTo("jkn")
        assertThat(qwerty.neighboursOf('z')).isEqualTo("asx")
        for (a in 'a'..'z') {
            assertThat(qwerty.neighboursOf(a)).isNotEmpty()
            assertThat(qwerty.isNeighbour(a.code, a.code)).isFalse()
            for (b in 'a'..'z') {
                assertThat(
                    qwerty.isNeighbour(a.code, b.code),
                ).isEqualTo(qwerty.isNeighbour(b.code, a.code))
            }
        }
        // Only folded a–z have neighbours: digits, accented letters (already folded away) and emoji never slip.
        assertThat(qwerty.isNeighbour('1'.code, '2'.code)).isFalse()
        assertThat(qwerty.isNeighbour('ø'.code, 'p'.code)).isFalse()
        assertThat(qwerty.isNeighbour(0x1F3B5, 'a'.code)).isFalse()
        assertThat(KeyboardLayout.QWERTZ.neighboursOf('z')).isEqualTo("ghtu")
        assertThat(KeyboardLayout.QWERTZ.neighboursOf('y')).isEqualTo("asx")
        assertThat(KeyboardLayout.AZERTY.neighboursOf('a')).isEqualTo("qz")
        assertThat(KeyboardLayout.AZERTY.neighboursOf('m')).isEqualTo("lp")
        assertThat(KeyboardLayout.NONE.neighboursOf('g')).isEmpty()
        // The index uses the layout it was built with: "ten" for "Zen Garden" is a slip on QWERTZ only.
        val zen = listOf(visible("Zen Garden", pkg = "com.example.zen"))
        assertThat(index(zen).labels("ten")).isEmpty()
        val qwertz = SearchIndex.build(zen, emptyMap(), ENGLISH, keyboard = KeyboardLayout.QWERTZ)
        assertThat(qwertz.tiers("ten")).containsExactly("Zen Garden" to MatchTier.ADJACENT)
        val none = SearchIndex.build(phoneLike, emptyMap(), ENGLISH, keyboard = KeyboardLayout.NONE)
        assertThat(none.labels("pg")).isEmpty()
    }

    /** T-SEARCH-29: short queries: "pg" finds Phone; one slip only, word starts only, only when few results. */
    @Test
    fun tSearch29_shortQueries() {
        assertThat(
            idx.tiers("pg"),
        ).containsExactly("Phone" to MatchTier.ADJACENT, "Photos" to MatchTier.ADJACENT).inOrder()
        // j is next to h, and to i: "pi" starts Pixelgram.
        assertThat(idx.labels("pj")).containsExactly("Phone", "Photos", "Pixelgram").inOrder()
        assertThat(idx.labels("oh")).containsExactly("Phone", "Photos").inOrder() // o is next to p
        assertThat(idx.labels("pgo")).containsExactly("Phone", "Photos").inOrder() // 3 characters: below fuzzy's 4
        // Two slips in two characters, or a key that is not a neighbour: nothing.
        assertThat(idx.labels("og")).isEmpty()
        assertThat(idx.labels("pm")).isEmpty()
        // Word starts only: "Graphite Sketch" has "ph" inside a word.
        assertThat(idx.labels("pg")).doesNotContain("Graphite Sketch")
        // A later word counts ("nw": w is next to e, "News" in "Brightside News"), above a substring ("Fernway").
        assertThat(idx.tiers("nw"))
            .containsExactly("Brightside News" to MatchTier.ADJACENT, "Fernway Travel" to MatchTier.SUBSTRING)
            .inOrder()
        // With 5 or more real matches, no slip is tried ("ca": Camera, Calendar, Calculator, Cards Vault, Podcasts).
        assertThat(idx.tiers("ca").map { it.second }).doesNotContain(MatchTier.ADJACENT)
        assertThat(idx.tiers("ca").size).isAtLeast(5)
    }

    /** T-SEARCH-29: one letter never slips: with one key typed there is no telling which key was meant. */
    @Test
    fun tSearch29_singleLetter() {
        // "z" and "j" are in no label here; their neighbours start 3 and 8 of the 41 labels.
        assertThat(idx.labels("z")).isEmpty()
        assertThat(idx.labels("j")).isEmpty()
        assertThat(idx.tiers("q")).containsExactly("Quillnote" to MatchTier.PREFIX)
    }

    /**
     * T-SEARCH-30: ranking: exact, prefix, word prefix and initials first; slips below them, above substring, package
     * and generic fuzzy matches; usage never crosses tiers.
     */
    @Test
    fun tSearch30_ranking() {
        val pgn = visible("Pgn Reader", pkg = "com.example.pgn")
        val guide = visible("Pocket Guide", pkg = "com.example.guide")
        val upgrade = visible("Upgrade Center", pkg = "com.example.upgrade")
        val apps = phoneLike + listOf(pgn, guide, upgrade)
        val expected =
            listOf(
                "Pgn Reader" to MatchTier.PREFIX,
                "Pocket Guide" to MatchTier.INITIALS,
                "Phone" to MatchTier.ADJACENT,
                "Photos" to MatchTier.ADJACENT,
                "Upgrade Center" to MatchTier.SUBSTRING,
            )
        assertThat(index(apps).tiers("pg")).containsExactlyElementsIn(expected).inOrder()
        // Heavy usage of Phone moves it ahead of Photos, never above the initials match.
        val boosted = index(apps, counts = mapOf(app("Phone").key to 10_000, upgrade.key to 10_000))
        assertThat(boosted.labels("pg"))
            .containsExactly(
                "Pgn Reader",
                "Pocket Guide",
                "Phone",
                "Photos",
                "Upgrade Center",
            ).inOrder()
        // A slip ranks above a generic fuzzy match: "nores" is Notes with r for t; "Norges Atlas" needs an insertion.
        val atlas = visible("Norges Atlas", pkg = "com.example.atlas")
        val notes = index(listOf(app("Notes"), atlas), counts = mapOf(atlas.key to 10_000))
        assertThat(notes.tiers("nores"))
            .containsExactly("Notes" to MatchTier.ADJACENT, "Norges Atlas" to MatchTier.FUZZY)
            .inOrder()
        // A slip at the label start ranks above one on a later word.
        val starts = index(listOf(visible("Bright News"), visible("Newt Diary")))
        assertThat(starts.labels("mew")).containsExactly("Newt Diary", "Bright News").inOrder()
        // Package names are never matched by slips.
        assertThat(index(listOf(visible("Zeta Tool", pkg = "com.example.phone"))).labels("phonw")).isEmpty()
    }

    /** T-SEARCH-30: longer queries: up to 2 slips from 5 characters, 3 from 10; more is not a slip. */
    @Test
    fun tSearch30_longerQueries() {
        assertThat(idx.tiers("phpne")).containsExactly("Phone" to MatchTier.ADJACENT)
        assertThat(idx.tiers("cslendsr").first()).isEqualTo("Calendar" to MatchTier.ADJACENT) // 2 slips (s for a)
        assertThat(idx.labels("xslendsr")).isEmpty() // 3 slips at 8 characters, and 3 edits
        assertThat(idx.tiers("calculatpe")).containsExactly("Calculator" to MatchTier.ADJACENT) // 10 characters
        assertThat(idx.tiers("xalculatpe")).containsExactly("Calculator" to MatchTier.ADJACENT) // 3 slips at 10
        assertThat(idx.labels("xslculatpe")).isEmpty() // 4 slips
        assertThat(Adjacent.maxSlips(1)).isEqualTo(1)
        assertThat(Adjacent.maxSlips(4)).isEqualTo(1)
        assertThat(Adjacent.maxSlips(5)).isEqualTo(2)
        assertThat(Adjacent.maxSlips(9)).isEqualTo(2)
        assertThat(Adjacent.maxSlips(10)).isEqualTo(3)
    }

    /**
     * T-SEARCH-30 (noise and recall): over all 702 one- and two-letter queries on the phone-like list, slips add at most
     * a handful of apps and never join a first pass of 5+ matches; and a slip on either of the first two letters of a
     * label finds that label whenever the gate lets slips run.
     */
    @Test
    fun tSearch30_noiseAndRecallOnRealisticList() {
        var worst = 0
        var worstQuery = ""
        var withSlips = 0
        var added = 0
        val queries = ('a'..'z').map { "$it" } + ('a'..'z').flatMap { a -> ('a'..'z').map { b -> "$a$b" } }
        for (q in queries) {
            val hits = idx.rank(q, includeHidden = true)
            val slips = hits.count { it.tier == MatchTier.ADJACENT }
            val labelHits = hits.count { it.tier != MatchTier.ADJACENT && it.tier != MatchTier.PACKAGE }
            if (labelHits >= SearchIndex.FUZZY_WHEN_FEWER_THAN) assertThat(slips).isEqualTo(0)
            if (slips > 0) withSlips++
            added += slips
            if (slips > worst) {
                worst = slips
                worstQuery = q
            }
        }
        val mean = added.toDouble() / withSlips
        println(
            "FB-015 noise: ${queries.size} queries, $withSlips with slips, mean %.2f added, worst \"%s\" adds %d of %d"
                .format(mean, worstQuery, worst, phoneLike.size),
        )
        assertThat(worst).isAtMost(MAX_SLIP_HITS)
        assertThat(mean).isAtMost(MAX_MEAN_SLIP_HITS)
        // Recall: every one-slip variant of each label's first two letters.
        val keys = KeyboardLayout.QWERTY
        var variants = 0
        var found = 0
        var gated = 0
        for (app in phoneLike) {
            val start = SearchFold.fold(app.displayLabel).take(2)
            for (pos in 0..1) {
                for (n in keys.neighboursOf(start[pos])) {
                    val q = if (pos == 0) "$n${start[1]}" else "${start[0]}$n"
                    val hits = idx.rank(q, includeHidden = true)
                    variants++
                    if (hits.any { it.app.key == app.key }) {
                        found++
                    } else {
                        // Missed only because the first pass was full.
                        assertThat(
                            hits.count { it.tier != MatchTier.PACKAGE },
                        ).isAtLeast(SearchIndex.FUZZY_WHEN_FEWER_THAN)
                        gated++
                    }
                }
            }
        }
        println("FB-015 recall: $found of $variants two-letter slips find their app ($gated gated by 5+ real matches)")
        assertThat(found.toDouble() / variants).isAtLeast(MIN_RECALL)
    }

    /** T-SEARCH-31 (R-002): distance over code points; no fuzzy pass for a query longer than every label. */
    @Test
    fun tSearch31_codePointsAndLongQueries() {
        val beat = visible("Beat🎵Box", pkg = "com.example.beat")
        val emoji = index(listOf(beat, visible("Calculator", pkg = "com.example.calc")))
        // One emoji left out is one edit, not two UTF-16 units.
        assertThat(emoji.tiers("beatbox")).containsExactly("Beat🎵Box" to MatchTier.FUZZY)
        assertThat(emoji.tiers("beat🎶box")).containsExactly("Beat🎵Box" to MatchTier.FUZZY)
        // Exactly the longest label plus the edit budget still matches; anything longer is skipped.
        assertThat(emoji.labels("calculatorxx")).containsExactly("Calculator")
        assertThat(emoji.labels("calculatorxxx")).isEmpty()
        val long = "calculator".repeat(10_000)
        val t0 = System.nanoTime()
        assertThat(emoji.labels(long)).isEmpty()
        assertThat((System.nanoTime() - t0) / 1e6).isLessThan(LONG_QUERY_CEILING_MS)
    }

    private companion object {
        const val MAX_SLIP_HITS = 8
        const val MAX_MEAN_SLIP_HITS = 3.0
        const val MIN_RECALL = 0.8
        const val LONG_QUERY_CEILING_MS = 500.0
    }
}
