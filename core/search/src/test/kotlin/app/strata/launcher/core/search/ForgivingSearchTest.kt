package app.strata.launcher.core.search

import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.testing.fixtures.SearchFixtures.visible
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * T-SEARCH-17…22 (Proposed, owner request "typing the exact thing is annoying"): word prefixes, initials with
 * camel-case words, package names, diacritics, typo tolerance and the fuzzy gate (FR-SEARCH-02 as amended).
 * Labels and package names are invented; each one has the shape the rule under test needs.
 */
class ForgivingSearchTest {
    private val tubeMusic = visible("PlayTube Music", pkg = "org.example.tubemusic")
    private val tube = visible("PlayTube", pkg = "org.example.tube")
    private val gmemo = visible("Gmemo", pkg = "com.example.memo")
    private val grandMaps = visible("Grand Maps", pkg = "com.example.maps")
    private val keyboard = visible("Northwind QuickType Keyboard", pkg = "com.typecraft.keys")
    private val fotografo = visible("Fotógrafo", pkg = "com.example.lens")
    private val chatty = visible("ChattyApp", pkg = "com.example.chatty")
    private val soundify = visible("Soundify", pkg = "com.example.stream.music")
    private val pixelgram = visible("Pixelgram", pkg = "com.example.pixels.android")
    private val pocketMusic = visible("Pocket Music", pkg = "com.example.pocket")
    private val calculator = visible("Calculator", pkg = "com.example.calc")
    private val music = visible("Music", pkg = "fixture.plain.music")
    private val musicPlayer = visible("Music Player", pkg = "fixture.player")

    private val apps =
        listOf(
            tubeMusic,
            tube,
            gmemo,
            grandMaps,
            keyboard,
            fotografo,
            chatty,
            soundify,
            pixelgram,
            pocketMusic,
            calculator,
        )
    private val idx = index(apps)

    private fun SearchIndex.hit(
        q: String,
        app: VisibleApp,
    ): SearchHit? = rank(q, includeHidden = true).firstOrNull { it.app.key == app.key }

    /** T-SEARCH-17: the prefix of any word ("music" → "PlayTube Music"), camel-case words ("tube", "type"). */
    @Test
    fun tSearch17_wordPrefixAndCamelCase() {
        assertThat(idx.labels("music").take(2)).containsExactly("PlayTube Music", "Pocket Music")
        assertThat(idx.hit("music", tubeMusic)!!.tier).isEqualTo(MatchTier.WORD_PREFIX)
        assertThat(idx.labels("quicktype").first()).isEqualTo("Northwind QuickType Keyboard")
        assertThat(idx.hit("quicktype", keyboard)!!.tier).isEqualTo(MatchTier.WORD_PREFIX)
        // "Tube" starts a camel-case word of "PlayTube"; "Type" one of "QuickType".
        assertThat(idx.labels("tube")).containsAtLeast("PlayTube", "PlayTube Music")
        assertThat(idx.hit("tube", tube)!!.tier).isEqualTo(MatchTier.WORD_PREFIX)
        assertThat(idx.hit("type", keyboard)!!.tier).isEqualTo(MatchTier.WORD_PREFIX)
        assertThat(idx.hit("key", keyboard)!!.tier).isEqualTo(MatchTier.WORD_PREFIX)
    }

    /** T-SEARCH-18: initials and acronyms over spaces and camel-case boundaries. */
    @Test
    fun tSearch18_initials() {
        assertThat(idx.labels("ptm").first()).isEqualTo("PlayTube Music")
        assertThat(idx.hit("ptm", tubeMusic)!!.tier).isEqualTo(MatchTier.INITIALS)
        assertThat(idx.labels("pm").take(2)).containsExactly("PlayTube Music", "Pocket Music")
        assertThat(idx.labels("pt")).containsAtLeast("PlayTube", "PlayTube Music")
        assertThat(idx.labels("gm").take(2)).containsExactly("Gmemo", "Grand Maps").inOrder()
        assertThat(idx.hit("gm", gmemo)!!.tier).isEqualTo(MatchTier.PREFIX)
        assertThat(idx.hit("gm", grandMaps)!!.tier).isEqualTo(MatchTier.INITIALS)
        assertThat(idx.labels("nqk").first()).isEqualTo("Northwind QuickType Keyboard")
    }

    /** T-SEARCH-19: substring anywhere; diacritics and case folded; package names; generic segments never match alone. */
    @Test
    fun tSearch19_substringDiacriticsPackage() {
        assertThat(idx.hit("dify", soundify)!!.tier).isEqualTo(MatchTier.SUBSTRING)
        assertThat(idx.labels("fotografo")).containsExactly("Fotógrafo")
        assertThat(idx.hit("FOTOGRAFO", fotografo)!!.tier).isEqualTo(MatchTier.EXACT)
        assertThat(idx.labels("typecraft")).containsExactly("Northwind QuickType Keyboard")
        assertThat(idx.hit("typecraft", keyboard)!!.tier).isEqualTo(MatchTier.SUBSTRING)
        assertThat(idx.hit("typecraft", keyboard)!!.score).isEqualTo(MatchTier.SUBSTRING.base)
        assertThat(idx.labels("com.example.chatty")).containsExactly("ChattyApp")
        // Soundify's package ends in ".music": a package match, below every label match.
        val musicHits = idx.labels("music")
        assertThat(musicHits.last()).isEqualTo("Soundify")
        assertThat(idx.labels("com")).isEmpty()
        assertThat(idx.labels("android")).isEmpty()
        assertThat(idx.labels("app")).containsExactly("ChattyApp") // label substring only
    }

    /** T-SEARCH-20: typo tolerance: 1 edit for 4–7 characters, 2 from 8, OSA (transpositions count once). */
    @Test
    fun tSearch20_typos() {
        assertThat(idx.labels("chattyap").first()).isEqualTo("ChattyApp")
        assertThat(idx.labels("soundfy")).containsExactly("Soundify")
        assertThat(idx.hit("soundfy", soundify)!!.tier).isEqualTo(MatchTier.FUZZY)
        assertThat(idx.labels("pixlgram")).containsExactly("Pixelgram")
        assertThat(idx.labels("playtbe")).contains("PlayTube")
        assertThat(idx.labels("chtatyapp")).containsExactly("ChattyApp") // transposition
        assertThat(idx.labels("kalkulator")).containsExactly("Calculator") // 2 edits, 10 characters
        assertThat(idx.labels("keybaord")).containsExactly("Northwind QuickType Keyboard") // a later word
        assertThat(idx.labels("musci")).containsAtLeast("Pocket Music", "PlayTube Music")
        // Too many edits for the length.
        assertThat(idx.labels("sndfy")).isEmpty() // 5 characters, 3 edits
        assertThat(idx.labels("spx")).isEmpty() // under 4 characters: never fuzzy
        assertThat(idx.labels("kxlkxlxtor")).isEmpty() // 3 edits
    }

    /** T-SEARCH-21: exact and prefix still rank first; usage never lifts a result into a higher tier. */
    @Test
    fun tSearch21_exactAndPrefixFirst() {
        val all = index(apps + listOf(music, musicPlayer))
        val hits = all.rank("music", includeHidden = true)
        assertThat(hits.map { it.app.displayLabel }.take(4))
            .containsExactly("Music", "Music Player", "PlayTube Music", "Pocket Music")
            .inOrder()
        assertThat(hits.map { it.tier.ordinal }).isInOrder()
        assertThat(hits.first().tier).isEqualTo(MatchTier.EXACT)
        // Heavy usage of word-prefix, initials and fuzzy matches does not lift them above the prefix tiers.
        val boosted =
            index(
                apps + listOf(music, musicPlayer),
                counts = mapOf(pocketMusic.key to 10_000, grandMaps.key to 10_000, soundify.key to 10_000),
            )
        assertThat(boosted.labels("music").take(2)).containsExactly("Music", "Music Player").inOrder()
        assertThat(boosted.labels("gm").first()).isEqualTo("Gmemo")
        // Within a tier the boost decides, then collation.
        assertThat(boosted.labels("music").subList(2, 4)).containsExactly("Pocket Music", "PlayTube Music").inOrder()
        val tiers = boosted.rank("musi", includeHidden = true).map { it.tier.ordinal }
        assertThat(tiers).isInOrder()
    }

    /** T-SEARCH-22: fuzzy matching runs only when the earlier tiers found fewer than 5 results. */
    @Test
    fun tSearch22_fuzzyOnlyWhenFewResults() {
        val nite = visible("Nite Mode")
        val four = listOf("Notes", "Keep Notes", "Notebook", "Footnote").map { visible(it) }
        val withFour = index(four + nite)
        assertThat(withFour.labels("note")).hasSize(5)
        assertThat(
            withFour
                .rank("note", true)
                .last()
                .app.key,
        ).isEqualTo(nite.key)
        assertThat(withFour.rank("note", true).last().tier).isEqualTo(MatchTier.FUZZY)
        val withFive = index(four + visible("Denote") + nite)
        assertThat(withFive.labels("note")).hasSize(5)
        assertThat(withFive.labels("note")).doesNotContain("Nite Mode")
        assertThat(SearchIndex.FUZZY_WHEN_FEWER_THAN).isEqualTo(5)
    }

    @Test
    fun splitCamelAndEditBudget() {
        assertThat(SearchFold.splitCamel("PlayTube Music")).isEqualTo("Play Tube Music")
        assertThat(SearchFold.splitCamel("QuickType")).isEqualTo("Quick Type")
        assertThat(SearchFold.splitCamel("K9Mail")).isEqualTo("K 9 Mail")
        assertThat(SearchFold.splitCamel("Maps")).isSameInstanceAs("Maps")
        assertThat(SearchFold.splitCamel("ÉCRAN")).isEqualTo("ÉCRAN")
        assertThat(Fuzzy.maxEdits(3)).isEqualTo(0)
        assertThat(Fuzzy.maxEdits(4)).isEqualTo(1)
        assertThat(Fuzzy.maxEdits(7)).isEqualTo(1)
        assertThat(Fuzzy.maxEdits(8)).isEqualTo(2)
        val s = Fuzzy.Scratch(4)
        assertThat(Fuzzy.withinEditsOfPrefix("grandmaps", "grand maps", 0, 2, s)).isTrue()
        assertThat(Fuzzy.withinEditsOfPrefix("pixlgram", "pixelgram", 0, 1, s)).isTrue()
        assertThat(Fuzzy.withinEditsOfPrefix("keybaord", "northwind quicktype keyboard", 20, 1, s)).isTrue()
        assertThat(Fuzzy.withinEditsOfPrefix("keybaord", "northwind quicktype keyboard", 0, 1, s)).isFalse()
    }
}
