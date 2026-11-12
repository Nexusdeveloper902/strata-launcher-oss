package app.strata.launcher.platform.icons

import app.strata.launcher.core.model.IconSource
import app.strata.launcher.core.model.IconStyle
import app.strata.launcher.core.model.IconTintMode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** THEME_SYSTEM §4.6 precedence with the Strata glyph step (FR-THEME-35, T-THEME-54). */
class IconSourceResolverTest {
    private val none = IconStyle()
    private val mono = IconStyle(tint = IconTintMode.MONOCHROME)
    private val fallbackMono = IconStyle(packFallbackMonochrome = true)

    private fun r(
        style: IconStyle,
        override: Boolean = false,
        pack: Boolean = false,
        glyph: Boolean = false,
        monochrome: Boolean = false,
        api: Int = 34,
    ) = IconSourceResolver.resolve(style, override, pack, glyph, monochrome, api)

    @Test
    fun overrideThenPackWinOverEverything() {
        assertThat(
            r(mono, override = true, pack = true, glyph = true, monochrome = true),
        ).isEqualTo(IconSource.OVERRIDE)
        assertThat(r(mono, pack = true, glyph = true, monochrome = true)).isEqualTo(IconSource.PACK)
        assertThat(r(none, override = true)).isEqualTo(IconSource.OVERRIDE)
        assertThat(r(none, pack = true)).isEqualTo(IconSource.PACK)
    }

    @Test
    fun untintedIsPlainNeverGlyphOrMonochrome() {
        assertThat(r(none, glyph = true, monochrome = true)).isEqualTo(IconSource.PLAIN)
    }

    /** T-THEME-12 (U half): API 33+ uses the monochrome layer, otherwise the desaturated icon. */
    @Test
    fun tTheme12_monochromeOnlyFromApi33() {
        assertThat(r(mono, monochrome = true, api = 33)).isEqualTo(IconSource.PLATFORM_MONOCHROME)
        assertThat(r(mono, monochrome = true, api = 32)).isEqualTo(IconSource.DESATURATED)
        assertThat(r(mono, monochrome = false, api = 36)).isEqualTo(IconSource.DESATURATED)
        assertThat(r(mono, monochrome = true, api = 26)).isEqualTo(IconSource.DESATURATED)
        assertThat(r(IconStyle(tint = IconTintMode.ACCENT), monochrome = true, api = 33))
            .isEqualTo(IconSource.PLATFORM_MONOCHROME)
    }

    @Test
    fun glyphBeforeMonochromeWhenTinted() {
        assertThat(r(mono, glyph = true, monochrome = true)).isEqualTo(IconSource.GLYPH)
        assertThat(r(fallbackMono, glyph = true)).isEqualTo(IconSource.GLYPH)
    }

    /** `pack.fallback: monochrome`: apps the pack does not cover (or all, without the pack) are tinted. */
    @Test
    fun packFallbackMonochromeTintsUncoveredApps() {
        assertThat(
            r(fallbackMono.copy(packPackage = "p"), pack = false, monochrome = true),
        ).isEqualTo(IconSource.PLATFORM_MONOCHROME)
        assertThat(r(fallbackMono, monochrome = false)).isEqualTo(IconSource.DESATURATED)
        assertThat(r(IconStyle(packPackage = "p"), pack = false)).isEqualTo(IconSource.PLAIN)
    }

    /**
     * T-THEME-54: 12 cases over override, pack, glyph, monochrome layer and none; tint none/monochrome/accent;
     * `pack.fallback` system/monochrome; API 32 and 33.
     */
    @Test
    fun tTheme54_precedenceTable() {
        val accent = IconStyle(tint = IconTintMode.ACCENT)
        val packSystem = IconStyle(packPackage = "pack")
        val packMono = IconStyle(packPackage = "pack", packFallbackMonochrome = true)

        data class Case(
            val style: IconStyle,
            val override: Boolean = false,
            val pack: Boolean = false,
            val glyph: Boolean = false,
            val mono: Boolean = false,
            val api: Int = 33,
            val expected: IconSource,
        )
        val cases =
            listOf(
                // 1-4: the full order under a tint: override, pack, glyph, platform monochrome, desaturated.
                Case(mono, override = true, pack = true, glyph = true, mono = true, expected = IconSource.OVERRIDE),
                Case(mono, pack = true, glyph = true, mono = true, expected = IconSource.PACK),
                Case(mono, glyph = true, mono = true, expected = IconSource.GLYPH),
                Case(accent, mono = true, expected = IconSource.PLATFORM_MONOCHROME),
                // 5-6: monochrome layer only from API 33; else (and with no layer) desaturated.
                Case(accent, mono = true, api = 32, expected = IconSource.DESATURATED),
                Case(mono, expected = IconSource.DESATURATED),
                // 7-9: tint none + fallback system: Plain, never glyph/monochrome/desaturated; override/pack still win.
                Case(none, glyph = true, mono = true, expected = IconSource.PLAIN),
                Case(none, override = true, glyph = true, expected = IconSource.OVERRIDE),
                Case(packSystem, pack = true, glyph = true, expected = IconSource.PACK),
                // 10-11: pack.fallback monochrome follows glyph, monochrome, desaturated for apps the pack lacks.
                Case(packMono, glyph = true, mono = true, expected = IconSource.GLYPH),
                Case(packMono, mono = true, api = 32, expected = IconSource.DESATURATED),
                // 12: an uninstalled pack drops step 2 only (pack = false), the rest of the chain stays.
                Case(
                    packMono.copy(tint = IconTintMode.MONOCHROME),
                    mono = true,
                    expected = IconSource.PLATFORM_MONOCHROME,
                ),
            )
        assertThat(cases).hasSize(12)
        cases.forEachIndexed { i, c ->
            assertWithMessage("case ${i + 1}")
                .that(r(c.style, c.override, c.pack, c.glyph, c.mono, c.api))
                .isEqualTo(c.expected)
        }
        // An uninstalled pack with fallback system and no tint: Plain (the app's normal icon), not a glyph.
        assertThat(r(packSystem, pack = false, glyph = true)).isEqualTo(IconSource.PLAIN)
    }
}
