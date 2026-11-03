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

    @Test
    fun everyFrameObservationsCloseExactlyOnce() {
        // The pre-draw listener reports the same state on every frame; only the change decides.
        val t = ImeCloseTracker()
        repeat(3) { assertThat(t.onIme(visible = false, queryBlank = true)).isEqualTo(ImeChange.None) }
        repeat(3) { assertThat(t.onIme(visible = true, queryBlank = true)).isEqualTo(ImeChange.None) }
        assertThat(t.onIme(visible = false, queryBlank = true)).isEqualTo(ImeChange.HiddenClose)
        repeat(3) { assertThat(t.onIme(visible = false, queryBlank = true)).isEqualTo(ImeChange.None) }
    }

    @Test
    fun oneDrawnFrameWithTheImeUpIsEnoughToClose() {
        // CI run 41675534786 (API 34): the IME was up for about one second before Back; one frame that saw it
        // must be enough (item 18).
        val t = ImeCloseTracker()
        t.onIme(visible = false, queryBlank = true)
        t.onIme(visible = true, queryBlank = true)
        assertThat(t.onIme(visible = false, queryBlank = true)).isEqualTo(ImeChange.HiddenClose)
    }

    @Test
    fun queryTypedWhileImeUpThenClearedClosesOnHide() {
        // queryBlank is read at the hide, not when the IME appeared.
        val t = ImeCloseTracker()
        t.onIme(visible = true, queryBlank = false)
        t.onIme(visible = true, queryBlank = false)
        assertThat(t.onIme(visible = false, queryBlank = true)).isEqualTo(ImeChange.HiddenClose)
    }
}
