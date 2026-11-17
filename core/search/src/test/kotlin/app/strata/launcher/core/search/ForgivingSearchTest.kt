package app.strata.launcher.core.search

import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.testing.fixtures.SearchFixtures.visible
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * T-SEARCH-17…22 (Proposed, owner request "typing the exact thing is annoying"): word prefixes, initials with
 * camel-case words, package names, diacritics, typo tolerance and the fuzzy gate (FR-SEARCH-02 as amended).
 */
class ForgivingSearchTest {
    private val ytMusic = visible("YouTube Music", pkg = "com.google.android.apps.youtube.music")
    private val youTube = visible("YouTube", pkg = "com.google.android.youtube")
    private val gmail = visible("Gmail", pkg = "com.google.android.gm")
    private val googleMaps = visible("Google Maps", pkg = "com.google.android.apps.maps")
    private val swiftKey = visible("Microsoft SwiftKey Keyboard", pkg = "com.touchtype.swiftkey")
    private val camara = visible("Cámara", pkg = "com.sec.android.app.camera")
    private val whatsApp = visible("WhatsApp", pkg = "com.whatsapp")
    private val spotify = visible("Spotify", pkg = "com.spotify.music")
    private val instagram = visible("Instagram", pkg = "com.instagram.android")
    private val samsungMusic = visible("Samsung Music", pkg = "com.sec.android.app.music")
    private val calculator = visible("Calculator", pkg = "com.sec.android.app.popupcalculator")
    private val music = visible("Music", pkg = "fixture.plain.music")
    private val musicPlayer = visible("Music Player", pkg = "fixture.player")

    private val apps =
        listOf(
            ytMusic,
            youTube,
            gmail,
            googleMaps,
            swiftKey,
            camara,
            whatsApp,
            spotify,
            instagram,
            samsungMusic,
            calculator,
        )
    private val idx = index(apps)

    private fun SearchIndex.hit(
        q: String,
        app: VisibleApp,
    ): SearchHit? = rank(q, includeHidden = true).firstOrNull { it.app.key == app.key }

    /** T-SEARCH-17: the prefix of any word ("music" → YouTube Music), camel-case words ("tube", "swiftkey"). */
    @Test
    fun tSearch17_wordPrefixAndCamelCase() {
        assertThat(idx.labels("music").take(2)).containsExactly("Samsung Music", "YouTube Music")
        assertThat(idx.hit("music", ytMusic)!!.tier).isEqualTo(MatchTier.WORD_PREFIX)
        assertThat(idx.labels("swiftkey").first()).isEqualTo("Microsoft SwiftKey Keyboard")
        assertThat(idx.hit("swiftkey", swiftKey)!!.tier).isEqualTo(MatchTier.WORD_PREFIX)
        // "Tube" starts a camel-case word of "YouTube".
        assertThat(idx.labels("tube")).containsAtLeast("YouTube", "YouTube Music")
        assertThat(idx.hit("tube", youTube)!!.tier).isEqualTo(MatchTier.WORD_PREFIX)
        assertThat(idx.hit("key", swiftKey)!!.tier).isEqualTo(MatchTier.WORD_PREFIX)
    }

    /** T-SEARCH-18: initials and acronyms over spaces and camel-case boundaries. */
    @Test
    fun tSearch18_initials() {
        assertThat(idx.labels("ytm").first()).isEqualTo("YouTube Music")
        assertThat(idx.hit("ytm", ytMusic)!!.tier).isEqualTo(MatchTier.INITIALS)
        assertThat(idx.labels("ym").first()).isEqualTo("YouTube Music")
        assertThat(idx.labels("yt")).containsAtLeast("YouTube", "YouTube Music")
        assertThat(idx.labels("gm").take(2)).containsExactly("Gmail", "Google Maps").inOrder()
        assertThat(idx.hit("gm", gmail)!!.tier).isEqualTo(MatchTier.PREFIX)
        assertThat(idx.hit("gm", googleMaps)!!.tier).isEqualTo(MatchTier.INITIALS)
        assertThat(idx.labels("msk").first()).isEqualTo("Microsoft SwiftKey Keyboard")
    }

    /** T-SEARCH-19: substring anywhere; diacritics and case folded; package names; generic segments never match alone. */
    @Test
    fun tSearch19_substringDiacriticsPackage() {
        assertThat(idx.hit("tify", spotify)!!.tier).isEqualTo(MatchTier.SUBSTRING)
        assertThat(idx.labels("camara")).containsExactly("Cámara")
        assertThat(idx.hit("CAMARA", camara)!!.tier).isEqualTo(MatchTier.EXACT)
        assertThat(idx.labels("touchtype")).containsExactly("Microsoft SwiftKey Keyboard")
        assertThat(idx.hit("touchtype", swiftKey)!!.tier).isEqualTo(MatchTier.SUBSTRING)
        assertThat(idx.hit("touchtype", swiftKey)!!.score).isEqualTo(MatchTier.SUBSTRING.base)
        assertThat(idx.labels("com.whatsapp")).contains("WhatsApp")
        // Spotify's package ends in ".music": a package match, below every label match.
        val musicHits = idx.labels("music")
        assertThat(musicHits.last()).isEqualTo("Spotify")
        assertThat(idx.labels("com")).isEmpty()
        assertThat(idx.labels("android")).isEmpty()
        assertThat(idx.labels("app")).containsExactly("WhatsApp") // label substring only, not "…app…" packages
    }

    /** T-SEARCH-20: typo tolerance: 1 edit for 4–7 characters, 2 from 8, OSA (transpositions count once). */
    @Test
    fun tSearch20_typos() {
        assertThat(idx.labels("whatsap").first()).isEqualTo("WhatsApp")
        assertThat(idx.labels("spotfy")).containsExactly("Spotify")
        assertThat(idx.hit("spotfy", spotify)!!.tier).isEqualTo(MatchTier.FUZZY)
        assertThat(idx.labels("instgram")).containsExactly("Instagram")
        assertThat(idx.labels("youtbe")).contains("YouTube")
        assertThat(idx.labels("whtasapp")).containsExactly("WhatsApp") // transposition
        assertThat(idx.labels("kalkulator")).containsExactly("Calculator") // 2 edits, 10 characters
        assertThat(idx.labels("keybaord")).containsExactly("Microsoft SwiftKey Keyboard") // a later word
        assertThat(idx.labels("musci")).containsAtLeast("Samsung Music", "YouTube Music")
        // Too many edits for the length.
        assertThat(idx.labels("sptfy")).isEmpty() // 5 characters, 2 edits
        assertThat(idx.labels("spx")).isEmpty() // under 4 characters: never fuzzy
        assertThat(idx.labels("kxlkxlxtor")).isEmpty() // 3 edits
    }

    /** T-SEARCH-21: exact and prefix still rank first; usage never lifts a result into a higher tier. */
    @Test
    fun tSearch21_exactAndPrefixFirst() {
        val all = index(apps + listOf(music, musicPlayer))
        val hits = all.rank("music", includeHidden = true)
        assertThat(hits.map { it.app.displayLabel }.take(4))
            .containsExactly("Music", "Music Player", "Samsung Music", "YouTube Music")
            .inOrder()
        assertThat(hits.map { it.tier.ordinal }).isInOrder()
        assertThat(hits.first().tier).isEqualTo(MatchTier.EXACT)
        // Heavy usage of word-prefix, initials and fuzzy matches does not lift them above the prefix tiers.
        val boosted =
            index(
                apps + listOf(music, musicPlayer),
                counts = mapOf(ytMusic.key to 10_000, googleMaps.key to 10_000, spotify.key to 10_000),
            )
        assertThat(boosted.labels("music").take(2)).containsExactly("Music", "Music Player").inOrder()
        assertThat(boosted.labels("gm").first()).isEqualTo("Gmail")
        // Within a tier the boost decides, then collation.
        assertThat(boosted.labels("music").subList(2, 4)).containsExactly("YouTube Music", "Samsung Music").inOrder()
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
        assertThat(SearchFold.splitCamel("YouTube Music")).isEqualTo("You Tube Music")
        assertThat(SearchFold.splitCamel("SwiftKey")).isEqualTo("Swift Key")
        assertThat(SearchFold.splitCamel("K9Mail")).isEqualTo("K 9 Mail")
        assertThat(SearchFold.splitCamel("Maps")).isSameInstanceAs("Maps")
        assertThat(SearchFold.splitCamel("ÉCRAN")).isEqualTo("ÉCRAN")
        assertThat(Fuzzy.maxEdits(3)).isEqualTo(0)
        assertThat(Fuzzy.maxEdits(4)).isEqualTo(1)
        assertThat(Fuzzy.maxEdits(7)).isEqualTo(1)
        assertThat(Fuzzy.maxEdits(8)).isEqualTo(2)
        val s = Fuzzy.Scratch(4)
        assertThat(Fuzzy.withinEditsOfPrefix("googlemaps", "google maps", 0, 2, s)).isTrue()
        assertThat(Fuzzy.withinEditsOfPrefix("instgram", "instagram", 0, 1, s)).isTrue()
        assertThat(Fuzzy.withinEditsOfPrefix("keybaord", "microsoft swiftkey keyboard", 19, 1, s)).isTrue()
        assertThat(Fuzzy.withinEditsOfPrefix("keybaord", "microsoft swiftkey keyboard", 0, 1, s)).isFalse()
    }
}
