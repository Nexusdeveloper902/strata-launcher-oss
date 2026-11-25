package app.strata.launcher.core.ui

import app.strata.launcher.core.ui.SevenSegment.A
import app.strata.launcher.core.ui.SevenSegment.B
import app.strata.launcher.core.ui.SevenSegment.C
import app.strata.launcher.core.ui.SevenSegment.D
import app.strata.launcher.core.ui.SevenSegment.E
import app.strata.launcher.core.ui.SevenSegment.F
import app.strata.launcher.core.ui.SevenSegment.G
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** T-THEME-58: the seven-segment digits of the `segmented` and `countdownBoard` clock styles (VISUAL_SPEC §9.4). */
class SevenSegmentTest {
    @Test
    fun tTheme58_digitMasks() {
        val expected =
            listOf(
                A or B or C or D or E or F,
                B or C,
                A or B or D or E or G,
                A or B or C or D or G,
                B or C or F or G,
                A or C or D or F or G,
                A or C or D or E or F or G,
                A or B or C,
                A or B or C or D or E or F or G,
                A or B or C or D or F or G,
            )
        for (d in 0..9) assertThat(SevenSegment.mask(d)).isEqualTo(expected[d])
        // Every digit is distinct, and the lit-bar counts are the classic 6 2 5 5 4 5 6 3 7 6.
        assertThat((0..9).map { SevenSegment.mask(it) }.toSet()).hasSize(10)
        assertThat(
            (0..9).map {
                Integer.bitCount(SevenSegment.mask(it))
            },
        ).containsExactly(6, 2, 5, 5, 4, 5, 6, 3, 7, 6).inOrder()
    }

    @Test
    fun tTheme58_cellsFromLocaleDigits() {
        assertThat(
            SevenSegment.cells(" 9:05"),
        ).containsExactly(SevenSegment.BLANK, 9, SevenSegment.COLON, 0, 5).inOrder()
        // Arabic-Indic and Devanagari digits map by value: the board shows the same time.
        assertThat(SevenSegment.cells("٢١:١١")).isEqualTo(SevenSegment.cells("21:11"))
        assertThat(SevenSegment.cells("२१:०५")).isEqualTo(SevenSegment.cells("21:05"))
        // Anything else cannot be drawn: the caller falls back to text.
        assertThat(SevenSegment.cells("21h05")).isNull()
    }

    @Test
    fun tTheme58_width() {
        val h = 100f
        val cells = SevenSegment.cells("21:11:41")!!
        val expected = h * (6 * SevenSegment.DIGIT_WIDTH + 2 * SevenSegment.COLON_WIDTH + 7 * SevenSegment.CELL_GAP)
        assertThat(SevenSegment.width(cells, h)).isWithin(0.01f).of(expected)
        assertThat(SevenSegment.width(emptyList(), h)).isEqualTo(0f)
    }
}
