package app.strata.launcher.core.theme

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.theme.importing.BundleCodec
import app.strata.launcher.core.theme.model.ClockStyle
import app.strata.launcher.core.theme.parse.ThemeParser
import app.strata.launcher.core.theme.store.BundleExport
import app.strata.launcher.core.theme.store.PresetWallpaper
import app.strata.launcher.core.theme.store.PresetWallpaperList
import app.strata.launcher.core.theme.store.ThemeLibrary
import app.strata.launcher.testing.fakes.FakeClock
import app.strata.launcher.testing.fakes.InMemoryThemeFileStore
import app.strata.launcher.testing.fakes.InMemoryThemeIndexStore
import app.strata.launcher.testing.fakes.MapBuiltInThemeSource
import app.strata.launcher.testing.fixtures.BundleFixtures
import app.strata.launcher.testing.fixtures.ThemeFixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * T-THEME-61 (library half, FR-THEME-36): the wallpaper set of a built-in preset (THEME_SYSTEM §14.2) and the
 * `segmented` / `countdownBoard` clock style values (FR-THEME-37) in the schema model.
 */
class PresetWallpaperTest {
    private val images = (1..3).map { BundleFixtures.webpHeader(40 + it, 80) }
    private val shas = images.map(BundleCodec::sha256)
    private val preset = "set.strata-theme.json"
    private val presetId = "5e7c0a11-0000-4000-8000-00000000c0de"
    private val set = shas.mapIndexed { i, s -> PresetWallpaper(s, "Picture ${i + 1}") }
    private val builtIns =
        MapBuiltInThemeSource(
            files =
                ThemeFixtures.samples() +
                    (
                        preset to
                            (
                                """{"schemaVersion":1,"id":"$presetId","name":"With Wallpapers",""" +
                                    """"wallpaper":{"show":true,"image":{"sha256":"${shas[0]}"}}}"""
                            ).toByteArray()
                    ),
            wallpaperSets = mapOf(preset to set),
            images = shas.zip(images).toMap(),
        )
    private val files = InMemoryThemeFileStore()
    private val library =
        ThemeLibrary(files, InMemoryThemeIndexStore(), builtIns, FakeClock(1_800_000_000_000)).also {
            it.boot()
        }
    private val paperId = "c5017d5b-b2b0-48ff-83ed-33f77fd14754"

    @Test
    fun tTheme61_presetOffersItsSetInOrder() {
        assertThat(library.wallpaperChoices(presetId)).containsExactlyElementsIn(set).inOrder()
        assertThat(library.summaries().single { it.id == presetId }.hasWallpapers).isTrue()
        assertThat(library.summaries().single { it.id == paperId }.hasWallpapers).isFalse()
        assertThat(library.wallpaperChoices(paperId)).isEmpty()
    }

    @Test
    fun tTheme61_imagesComeFromTheBuiltInsAndAreVerified() {
        assertThat(library.wallpaperImage(presetId)!!.bytes).isEqualTo(images[0])
        assertThat(library.wallpaperImage(presetId, shas[2])!!.bytes).isEqualTo(images[2])
        // A hash outside the set, even a stored one, is refused.
        assertThat(library.wallpaperImage(presetId, "0".repeat(64))).isNull()
        assertThat(library.wallpaperImage(paperId, shas[1])).isNull()
        // The built-in images count as present for the resolver.
        assertThat(library.builtInImageNames()).containsExactlyElementsIn(shas)
    }

    @Test
    fun tTheme61_wrongBytesAreNotHandedOut() {
        val bad =
            MapBuiltInThemeSource(
                files = mapOf(preset to builtIns.read(preset)!!),
                wallpaperSets = mapOf(preset to set),
                images = mapOf(shas[0] to images[1]), // content does not match the name
            )
        val lib =
            ThemeLibrary(
                InMemoryThemeFileStore(),
                InMemoryThemeIndexStore(),
                bad,
                FakeClock(1),
            ).also { it.boot() }
        assertThat(lib.wallpaperImage(presetId)).isNull()
        assertThat(lib.wallpaperImage(presetId, shas[0])).isNull()
    }

    @Test
    fun tTheme61_aCopyOfThePresetKeepsTheSet_andExportsItsImage() {
        val copy = library.duplicate(presetId, "c0b1c0b1-0000-4000-8000-000000000001")!!
        assertThat(library.wallpaperChoices(copy)).containsExactlyElementsIn(set).inOrder()
        assertThat(library.wallpaperImage(copy)!!.bytes).isEqualTo(images[0])
        assertThat(library.hasBundleMedia(copy)).isTrue()
        val zip = (library.exportBundle(copy) as BundleExport.Ok).bytes
        val contents = (BundleCodec.read(zip) as Outcome.Ok).value
        assertThat(contents.image!!.bytes).isEqualTo(images[0])
    }

    @Test
    fun tTheme61_aStoredThemeOffersItsOwnImageNamedAfterIt() {
        files.write("images/${shas[1]}.webp", images[1])
        val id = "0ae0ae0a-0000-4000-8000-000000000002"
        library.save(
            json("""{"schemaVersion":1,"id":"$id","name":"Own","wallpaper":{"image":{"sha256":"${shas[1]}"}}}"""),
        )
        assertThat(library.wallpaperChoices(id)).containsExactly(PresetWallpaper(shas[1], "Own"))
    }

    @Test
    fun tTheme61_listFormat() {
        val text =
            "# comment\n\n${shas[0]} First picture\n${shas[0]} again\nnot a line\n${shas[1]}   Second  \r\n" +
                "${shas[2]} ${"x".repeat(65)}\n"
        assertThat(PresetWallpaperList.parse(text))
            .containsExactly(PresetWallpaper(shas[0], "First picture"), PresetWallpaper(shas[1], "Second"))
            .inOrder()
        assertThat(PresetWallpaperList.fileFor("a.strata-theme.json")).isEqualTo("a.wallpapers.txt")
    }

    @Test
    fun tTheme58_segmentedClockStylesParse() {
        for ((wire, style) in listOf(
            "segmented" to ClockStyle.SEGMENTED,
            "countdownBoard" to ClockStyle.COUNTDOWN_BOARD,
        )) {
            val bytes = """{"schemaVersion":1,"id":"$presetId","name":"C","clock":{"style":"$wire"}}""".toByteArray()
            val parsed = (ThemeParser().parse(bytes) as Outcome.Ok).value
            assertThat(parsed.document.clock.style).isEqualTo(style)
            assertThat(parsed.isStrictlyValid).isTrue()
        }
    }
}
