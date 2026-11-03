package app.strata.launcher.core.search

import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.testing.fixtures.SearchFixtures
import app.strata.launcher.testing.fixtures.SearchFixtures.visible
import com.google.common.truth.Truth.assertThat
import com.ibm.icu.text.Transliterator
import org.junit.Test
import java.text.Collator
import java.util.Locale

/** JVM stand-in for `AndroidTextFolding` (ICU4J, test-only per ARCHITECTURE §15). */
object Icu4jTransliterate : Transliterate {
    private val t: Transliterator = Transliterator.getInstance("Any-Latin; Latin-ASCII")

    override fun transliterate(label: String): String? = synchronized(t) { t.transliterate(label) }
}

val ENGLISH: Comparator<String> =
    Collator
        .getInstance(
            Locale.ENGLISH,
        ).let { c -> Comparator { a, b -> c.compare(a, b) } }

fun index(
    apps: List<VisibleApp>,
    counts: Map<AppKey, Int> = emptyMap(),
    transliterate: Transliterate? = Icu4jTransliterate,
): SearchIndex = SearchIndex.build(apps, counts, ENGLISH, transliterate)

fun SearchIndex.labels(
    q: String,
    includeHidden: Boolean = true,
): List<String> = rank(q, includeHidden).map { it.app.displayLabel }

class SearchFoldTest {
    @Test
    fun foldRules() {
        assertThat(SearchFold.fold("Café")).isEqualTo("cafe")
        assertThat(SearchFold.fold("  Google   Maps ")).isEqualTo("google maps")
        assertThat(SearchFold.fold("Straße")).isEqualTo("strasse")
        assertThat(SearchFold.fold("STRAẞE")).isEqualTo("strasse")
        assertThat(SearchFold.fold("İstanbul ılık")).isEqualTo("istanbul ilik")
        assertThat(SearchFold.fold("Ørsted Æble Đak Łódź")).isEqualTo("orsted aeble dak lodz")
        assertThat(SearchFold.fold("K-9_Mail.app·x'y’z")).isEqualTo("k 9 mail app x y z")
        assertThat(SearchFold.fold("２０４８ Tiles")).isEqualTo("2048 tiles") // NFKD folds full-width digits
        assertThat(SearchFold.fold("C++ & Co")).isEqualTo("c++ & co")
        assertThat(SearchFold.fold("---")).isEmpty()
        assertThat(SearchFold.fold("")).isEmpty()
    }

    @Test
    fun nonLatinDetection() {
        assertThat(SearchFold.needsTransliteration(SearchFold.fold("Ñandú"))).isFalse()
        assertThat(SearchFold.needsTransliteration(SearchFold.fold("テレビ"))).isTrue()
        assertThat(SearchFold.needsTransliteration(SearchFold.fold("Почта"))).isTrue()
        assertThat(SearchFold.needsTransliteration("2048")).isFalse()
    }

    @Test
    fun fuzzyWithinOneEdit() {
        assertThat(Fuzzy.withinOneEditOfPrefix("mpas", "maps")).isTrue() // transposition
        assertThat(Fuzzy.withinOneEditOfPrefix("mapz", "maps")).isTrue() // substitution
        assertThat(Fuzzy.withinOneEditOfPrefix("mapps", "maps")).isTrue() // extra char in query
        assertThat(Fuzzy.withinOneEditOfPrefix("mas", "maps")).isTrue() // missing char
        assertThat(Fuzzy.withinOneEditOfPrefix("clendar", "calendar")).isTrue()
        assertThat(Fuzzy.withinOneEditOfPrefix("mzpz", "maps")).isFalse()
        assertThat(Fuzzy.withinOneEditOfPrefix("pmas", "maps")).isFalse()
    }
}

class SearchIndexTest {
    private val fixture = SearchFixtures.LABELS.map { visible(it) }
    private val idx = index(fixture)

    /** T-SEARCH-04: the ranking table `fixtures/search/ranking.csv` over 60 labels. */
    @Test
    fun tSearch04_rankingTable() {
        assertThat(SearchFixtures.LABELS).hasSize(60)
        val failures = mutableListOf<String>()
        for (row in SearchFixtures.RANKING) {
            val got = idx.labels(row.query)
            val ok =
                when {
                    row.check == "first" -> got.firstOrNull() == row.expected
                    row.check.startsWith("top:") -> row.expected in got.take(row.check.removePrefix("top:").toInt())
                    row.check == "absent" -> row.expected !in got
                    row.check == "none" -> got.isEmpty()
                    else -> error("bad check ${row.check}")
                }
            if (!ok) failures += "${row.query} ${row.check} ${row.expected}: got ${got.take(5)}"
        }
        assertThat(failures).isEmpty()
    }

    /** T-SEARCH-04: "ca" — exact/prefix tiers come before word-prefix, initials and substring. */
    @Test
    fun tSearch04_tiersOrdered() {
        val hits = idx.rank("ca", includeHidden = true)
        val tiers = hits.map { it.tier.ordinal }
        assertThat(tiers).isInOrder()
        assertThat(hits.first().tier).isEqualTo(MatchTier.PREFIX)
        assertThat(hits.map { it.tier }).contains(MatchTier.SUBSTRING) // "Podcasts", "Scanner"…
        val prefix = hits.filter { it.tier == MatchTier.PREFIX }.map { it.app.displayLabel }
        assertThat(prefix).containsExactly("Café", "Calculator", "Calendar", "Camera").inOrder()
    }

    /** T-SEARCH-04: ties break by launch count, then collation; usage never lifts a result a tier. */
    @Test
    fun tSearch04_tiesByLaunchCountThenCollation() {
        val maps = fixture.first { it.displayLabel == "Maps" }
        val mapster = fixture.first { it.displayLabel == "Mapster" }
        val google = fixture.first { it.displayLabel == "Google Maps" }
        assertThat(idx.labels("map").take(2)).containsExactly("Maps", "Mapster").inOrder()
        val boosted = index(fixture, mapOf(mapster.key to 3, google.key to 10_000))
        assertThat(
            boosted.labels("map").take(4),
        ).containsExactly("Mapster", "Maps", "Google Maps", "Amazing Maps").inOrder()
        val g = boosted.rank("map", true).first { it.app.key == google.key }
        assertThat(g.score).isEqualTo(MatchTier.WORD_PREFIX.base + SearchIndex.DISPLAY_BONUS + SearchIndex.MAX_BOOST)
        assertThat(boosted.rank("map", true).first { it.app.key == maps.key }.score)
            .isGreaterThan(g.score)
    }

    /** T-SEARCH-06: custom and system label both match; the custom match outranks a system-label match. */
    @Test
    fun tSearch06_customAndSystemLabel() {
        val renamed = visible("Maps", custom = "Nav")
        val other = visible("Navigation Toolkit", custom = "Toolkit")
        val i = index(listOf(renamed, other, visible("Weather")))
        assertThat(i.labels("nav")).containsExactly("Nav", "Toolkit").inOrder()
        val hit = i.rank("maps", true).single()
        assertThat(hit.app.key).isEqualTo(renamed.key)
        assertThat(hit.onDisplayLabel).isFalse()
        assertThat(i.rank("nav", true).first().onDisplayLabel).isTrue()
    }

    /** T-SEARCH-07 (unit half): hidden apps only while "Show hidden apps in search" is on. */
    @Test
    fun tSearch07_hiddenApps() {
        val h = visible("Hidden Diary", hidden = true)
        val i = index(listOf(h, visible("Home")))
        assertThat(i.labels("hid", includeHidden = true)).containsExactly("Hidden Diary")
        assertThat(
            i
                .rank("hid", includeHidden = true)
                .single()
                .app.hidden,
        ).isTrue()
        assertThat(i.labels("hid", includeHidden = false)).isEmpty()
    }

    /** T-SEARCH-05 (JVM half, ICU4J): native and transliterated queries; transliteration ranks a tier lower. */
    @Test
    fun tSearch05_transliterationJvm() {
        val tv = visible("テレビ")
        val withIcu = index(listOf(tv, visible("Terrace")))
        assertThat(withIcu.labels("テレ")).containsExactly("テレビ")
        val tere = withIcu.rank("tere", true).first()
        assertThat(tere.app.displayLabel).isEqualTo("テレビ")
        assertThat(tere.viaTransliteration).isTrue()
        assertThat(tere.score).isEqualTo(MatchTier.PREFIX.base - SearchIndex.TIER_GAP + SearchIndex.DISPLAY_BONUS)
        // API 26 equivalent: no transliterator.
        val without = index(listOf(tv), transliterate = null)
        assertThat(without.labels("テレ")).containsExactly("テレビ")
        assertThat(without.labels("tere")).doesNotContain("テレビ")
    }

    @Test
    fun translitCacheAndLatinLabelsSkipped() {
        val calls = mutableListOf<String>()
        val counting =
            Transliterate {
                calls += it
                Icu4jTransliterate.transliterate(it)
            }
        val cache = HashMap<String, String>()
        val apps = listOf(visible("テレビ"), visible("Café"), visible("Ñandú"))
        SearchIndex.build(apps, emptyMap(), ENGLISH, counting, cache)
        SearchIndex.build(apps, emptyMap(), ENGLISH, counting, cache)
        assertThat(calls).containsExactly("テレビ")
    }

    @Test
    fun emptyQueryAndFailingTransliterator() {
        assertThat(idx.rank("  ", true)).isEmpty()
        assertThat(idx.rank("-", true)).isEmpty()
        val i = SearchIndex.build(listOf(visible("テレビ")), emptyMap(), ENGLISH, { error("boom") })
        assertThat(i.labels("テ")).containsExactly("テレビ")
    }
}
