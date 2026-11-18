package app.strata.launcher.feature.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** T-THEME-56 helper: which glyphs bound a clock line on screen (their bearings centre its ink, FB-007). */
class VisualEndsTest {
    @Test
    fun digitsRunLeftToRightInBothDirections() {
        assertThat(visualEnds("21:05", rtl = false)).isEqualTo('2' to '5')
        assertThat(visualEnds("21:05", rtl = true)).isEqualTo('2' to '5')
    }

    @Test
    fun rightToLeftLettersAreReversed() {
        // Hebrew alef … tav: the first character is drawn on the right.
        assertThat(visualEnds("אבת", rtl = true)).isEqualTo('ת' to 'א')
    }

    @Test
    fun mixedRunsUseTheOuterRuns() {
        // In a right-to-left paragraph the first logical run (the digits) is drawn on the right, the Hebrew letter left.
        assertThat(visualEnds("9:41 א", rtl = true)).isEqualTo('א' to '1')
    }
}
