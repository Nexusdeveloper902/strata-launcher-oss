package app.strata.launcher.core.theme

import app.strata.launcher.core.theme.contrast.Contrast
import app.strata.launcher.core.theme.contrast.ContrastPair
import app.strata.launcher.core.theme.model.ThemeDocument
import app.strata.launcher.core.theme.model.Variant
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.ThemeGlobals
import app.strata.launcher.core.theme.resolve.ThemeResolver
import app.strata.launcher.core.theme.resolve.WallpaperColorsInput
import app.strata.launcher.testing.fixtures.ThemeFixtures
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.util.Locale

/** T-THEME-23 (contrast math), T-CI-06 (built-in gate), T-A11Y-11 (focus ring), T-THEME-46 (built-ins AA). */
class ContrastTest {
    /** Built-ins of THEME_SYSTEM §14: Strata Default plus the five samples (shipped as assets). */
    private val builtIns: Map<String, ThemeDocument> =
        mapOf("<default>" to StrataDefault.document) +
            ThemeFixtures.SAMPLE_NAMES.associate { it.removeSuffix(".strata-theme.json") to sample(it.removeSuffix(".strata-theme.json")).document }

    private fun caseName(
        v: Variant,
        pureBlack: Boolean,
    ) = v.wire + if (pureBlack) "+pureBlack" else ""

    private fun bgName(p: ContrastPair) = p.bg?.wire ?: "EFFECTIVE"

    @Test
    fun `T-THEME-23 reproduces every ratio of the section 14_1 checker to 2 decimals`() {
        val golden =
            javaClass.classLoader.getResourceAsStream("golden/contrast-14_1.tsv")!!.bufferedReader().readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split('\t') }
        assertThat(golden).hasSize(104)
        val actual =
            builtIns.flatMap { (name, doc) ->
                Contrast.staticCheck(doc).flatMap { c ->
                    c.results.map { r -> listOf(name, caseName(c.variant, c.pureBlack), r.pair.fg.wire, bgName(r.pair), "%.2f".format(Locale.ROOT, r.ratio)) }
                }
            }
        assertThat(actual).containsExactlyElementsIn(golden)
    }

    @Test
    fun `T-THEME-23 section 14_1 table lowest ratios`() {
        fun lowest(
            name: String,
            text: Boolean,
        ): String =
            "%.2f".format(
                Locale.ROOT,
                Contrast.staticCheck(builtIns.getValue(name)).flatMap { it.results }
                    .filter { (it.pair.minimum == 4.5) == text }.minOf { it.ratio },
            )
        val expected =
            mapOf(
                "<default>" to ("6.23" to "5.81"),
                "paper" to ("5.85" to "6.22"),
                "graphite" to ("6.11" to "6.05"),
                "midnight-oled" to ("7.70" to "10.82"),
                "dusk" to ("5.07" to "3.60"),
                "harbor" to ("6.60" to "5.72"),
            )
        for ((name, pair) in expected) {
            assertWithMessage(name).that(lowest(name, text = true) to lowest(name, text = false)).isEqualTo(pair)
        }
    }

    @Test
    fun `T-THEME-23 WCAG reference values`() {
        assertThat(app.strata.launcher.core.theme.color.Wcag.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt())).isWithin(1e-9).of(21.0)
        assertThat(app.strata.launcher.core.theme.color.Wcag.contrast(0xFF777777.toInt(), 0xFFFFFFFF.toInt())).isWithin(0.005).of(4.48)
    }

    @Test
    fun `T-CI-06 every built-in passes P1 to P8 in light, dark and pure black`() {
        for ((name, doc) in builtIns) {
            for (c in Contrast.staticCheck(doc)) {
                for (r in c.results) {
                    assertWithMessage("$name ${caseName(c.variant, c.pureBlack)} ${r.pair}").that(r.ratio).isAtLeast(r.pair.minimum)
                }
            }
        }
    }

    @Test
    fun `T-A11Y-11 focus ring (accent) is at least 3 to 1 against the effective background of every built-in`() {
        for ((name, doc) in builtIns) {
            for (c in Contrast.staticCheck(doc)) {
                val ring = c.results.single { it.pair == ContrastPair.P3 }
                assertWithMessage("$name ${caseName(c.variant, c.pureBlack)}").that(ring.ratio).isAtLeast(3.0)
            }
        }
    }

    @Test
    fun `T-THEME-46 built-ins are AA on any wallpaper and on 5 runtime wallpaper colour sets`() {
        val wallpapers =
            listOf(
                WallpaperColorsInput(0xFF000000.toInt(), 0xFF101010.toInt(), 0xFF202020.toInt()),
                WallpaperColorsInput(0xFFFFFFFF.toInt(), 0xFFF0F0F0.toInt(), 0xFFE0E0E0.toInt(), supportsDarkText = true),
                WallpaperColorsInput(0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFFDD835.toInt()),
                WallpaperColorsInput(0xFF8E24AA.toInt(), 0xFFFF7043.toInt(), null),
                WallpaperColorsInput(0xFF808080.toInt(), null, null),
            )
        for ((name, doc) in builtIns) {
            assertWithMessage("$name static").that(Contrast.staticCheck(doc).all { it.passes }).isTrue()
            for (wc in wallpapers) {
                for (api in listOf(26, 30, 34)) {
                    for (dark in listOf(false, true)) {
                        val platform = PlatformInputs(apiLevel = api, systemDark = dark, wallpaperColors = wc)
                        val t = ThemeResolver.resolve(doc, platform, ThemeGlobals()).theme
                        val w = doc.wallpaper.copy(show = t.wallpaper.show, dim = t.wallpaper.dim, backgroundOpacity = t.wallpaper.backgroundOpacity)
                        assertWithMessage("$name $wc api $api dark $dark runtime")
                            .that(Contrast.passes(t.colors, w, wc.all))
                            .isTrue()
                    }
                }
            }
        }
    }
}
