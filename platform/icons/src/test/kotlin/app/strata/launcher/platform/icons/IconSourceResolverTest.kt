package app.strata.launcher.platform.icons

import app.strata.launcher.core.model.IconSource
import app.strata.launcher.core.model.IconStyle
import app.strata.launcher.core.model.IconTintMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** THEME_SYSTEM §4.6 precedence; the glyph step is ready for FR-THEME-35 (M8, T-THEME-54). */
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
}
