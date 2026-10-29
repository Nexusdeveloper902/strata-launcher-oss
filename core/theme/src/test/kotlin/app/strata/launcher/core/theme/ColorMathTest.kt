package app.strata.launcher.core.theme

import app.strata.launcher.core.theme.color.Hct
import app.strata.launcher.core.theme.color.Lab
import app.strata.launcher.core.theme.color.Rgb
import app.strata.launcher.core.theme.color.TonalPalette
import app.strata.launcher.core.theme.color.TonalSpot
import app.strata.launcher.core.theme.color.Wcag
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** HCT and palette maths behind §5.2 and the tone moves of §7.5. */
class ColorMathTest {
    @Test
    fun hexRoundTrip() {
        assertThat(Rgb.parseHex("#2f5D8a")).isEqualTo(0xFF2F5D8A.toInt())
        assertThat(Rgb.toHex(0xFF2F5D8A.toInt())).isEqualTo("#2F5D8A")
        assertThat(Rgb.parseHex("#12345")).isNull()
        assertThat(Rgb.parseHex("#1234567")).isNull()
        assertThat(Rgb.parseHex("12345G")).isNull()
    }

    @Test
    fun toneIsLstar() {
        assertThat(Hct.fromArgb(Rgb.BLACK).tone).isWithin(1e-6).of(0.0)
        assertThat(Hct.fromArgb(Rgb.WHITE).tone).isWithin(1e-3).of(100.0)
        assertThat(Hct.fromArgb(0xFF777777.toInt()).tone).isWithin(0.1).of(50.0)
        assertThat(Hct.fromArgb(0xFF777777.toInt()).chroma).isLessThan(3.0)
    }

    @Test
    fun hctRoundTripsSrgbColours() {
        val rnd = Random(7)
        repeat(300) {
            val c = Rgb.of(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            val h = Hct.fromArgb(c)
            val back = h.toArgb()
            val d = maxOf(abs(Rgb.red(c) - Rgb.red(back)), abs(Rgb.green(c) - Rgb.green(back)), abs(Rgb.blue(c) - Rgb.blue(back)))
            assertWithMessage("${Rgb.toHex(c)} -> ${Rgb.toHex(back)}").that(d).isAtMost(4)
        }
    }

    @Test
    fun solvedColoursHitTheRequestedToneAndKeepHue() {
        for (hue in listOf(0.0, 27.0, 90.0, 150.0, 220.0, 282.0, 330.0)) {
            val p = TonalPalette(hue, 36.0)
            for (t in listOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 95)) {
                val c = p.tone(t)
                assertWithMessage("hue $hue tone $t").that(Lab.lstarFromArgb(c)).isWithin(0.6).of(t.toDouble())
                val back = Hct.fromArgb(c)
                if (back.chroma > 8) {
                    val dh = abs(((back.hue - hue + 540) % 360) - 180)
                    assertWithMessage("hue $hue tone $t").that(dh).isLessThan(4.0)
                }
            }
        }
        assertThat(TonalPalette(120.0, 30.0).tone(0)).isEqualTo(Rgb.BLACK)
        assertThat(TonalPalette(120.0, 30.0).tone(100)).isEqualTo(Rgb.WHITE)
    }

    @Test
    fun tonalSpotSchemesHaveMaterialContrast() {
        for (seed in listOf(0xFF6750A4.toInt(), 0xFF1E88E5.toInt(), 0xFFFDD835.toInt(), 0xFF2E7D32.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
            for (dark in listOf(false, true)) {
                val s = TonalSpot.scheme(seed, dark)
                assertWithMessage("${Rgb.toHex(seed)} dark=$dark").that(Wcag.contrast(s.onSurface, s.surface)).isAtLeast(7.0)
                assertWithMessage("${Rgb.toHex(seed)} dark=$dark").that(Wcag.contrast(s.onPrimary, s.primary)).isAtLeast(4.5)
            }
        }
    }
}
