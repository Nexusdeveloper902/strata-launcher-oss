package app.strata.launcher.feature.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The empty-query "one Back closes" decision (docs/impl-notes/M2.md items 8, 16, 17); part of T-SEARCH-10. */
class ImeCloseTrackerTest {
    @Test
    fun visibleThenHiddenWithBlankQueryCloses() {
        val t = ImeCloseTracker()
        assertThat(t.onIme(visible = false, queryBlank = true)).isEqualTo(ImeChange.None)
        assertThat(t.onIme(visible = true, queryBlank = true)).isEqualTo(ImeChange.None)
        assertThat(t.onIme(visible = false, queryBlank = true)).isEqualTo(ImeChange.HiddenClose)
    }

    @Test
    fun openingWhileImeAlreadyReportedVisibleStillClosesOnHide() {
        // A reopened layer whose first observation is "visible" (IME still up, or a stale report).
        val t = ImeCloseTracker()
        assertThat(t.onIme(visible = true, queryBlank = true)).isEqualTo(ImeChange.None)
        assertThat(t.onIme(visible = false, queryBlank = true)).isEqualTo(ImeChange.HiddenClose)
    }

    @Test
    fun scrollHideDoesNotCloseButTheNextBackDoes() {
        val t = ImeCloseTracker()
        t.onIme(visible = true, queryBlank = true)
        t.onResultsDrag()
        assertThat(t.onIme(visible = false, queryBlank = true)).isEqualTo(ImeChange.HiddenKeep)
        t.onIme(visible = true, queryBlank = true)
        assertThat(t.onIme(visible = false, queryBlank = true)).isEqualTo(ImeChange.HiddenClose)
    }

    @Test
    fun dragWhileImeHiddenDoesNotSwallowALaterBack() {
        // Drag with the IME already hidden: no hide edge consumes the mark. Showing the IME again must clear it.
        val t = ImeCloseTracker()
        t.onIme(visible = true, queryBlank = false)
        assertThat(t.onIme(visible = false, queryBlank = false)).isEqualTo(ImeChange.HiddenKeep)
        t.onResultsDrag()
        t.onIme(visible = true, queryBlank = true)
        assertThat(t.onIme(visible = false, queryBlank = true)).isEqualTo(ImeChange.HiddenClose)
    }

    @Test
    fun nonEmptyQueryDoesNotClose() {
        val t = ImeCloseTracker()
        t.onIme(visible = true, queryBlank = false)
        assertThat(t.onIme(visible = false, queryBlank = false)).isEqualTo(ImeChange.HiddenKeep)
    }

    @Test
    fun hiddenWithoutPriorVisibleDoesNothing() {
        val t = ImeCloseTracker()
        assertThat(t.onIme(visible = false, queryBlank = true)).isEqualTo(ImeChange.None)
        assertThat(t.onIme(visible = false, queryBlank = true)).isEqualTo(ImeChange.None)
    }
}
