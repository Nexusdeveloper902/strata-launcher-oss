package app.strata.launcher.feature.search

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** What an IME visibility change means for the search layer (items 8, 17, 18 of docs/impl-notes/M2.md). */
internal enum class ImeChange {
    /** No visible → hidden edge. */
    None,

    /** The IME hid with an empty query and not because of a results drag: this is the Back that closes search. */
    HiddenClose,

    /** The IME hid, but search stays (query not empty, or a results drag hid it). */
    HiddenKeep,
}

/**
 * Edge detector for "one Back closes search" with an empty query. Feed it every observed IME state (repeats are
 * fine: only a visible → hidden change decides); the first observation counts, so a layer that opens while the IME is (or is still reported) up closes on the next hide.
 * A results drag ([onResultsDrag]) marks the next hide as not-a-Back; any "visible" observation clears that
 * mark, so a drag while the IME was already hidden cannot swallow a later Back.
 */
internal class ImeCloseTracker {
    private var wasVisible = false
    private var hiddenByScroll = false

    /** Whether a results drag has marked the next hide (for the debug close log). */
    val dragMarked: Boolean get() = hiddenByScroll

    /** A results drag started; it hides the IME (if up), and that hide must not close search. */
    fun onResultsDrag() {
        hiddenByScroll = true
    }

    fun onIme(
        visible: Boolean,
        queryBlank: Boolean,
    ): ImeChange {
        val hid = wasVisible && !visible
        wasVisible = visible
        if (visible) hiddenByScroll = false
        if (!hid) return ImeChange.None
        val byScroll = hiddenByScroll
        hiddenByScroll = false
        return if (queryBlank && !byScroll) ImeChange.HiddenClose else ImeChange.HiddenKeep
    }
}

/**
 * Closes search on the IME-hide edge (docs/impl-notes/M2.md items 8, 16–18). All API levels read
 * `ViewCompat.getRootWindowInsets(view).isVisible(ime())` on every pre-draw and feed [tracker] right there:
 * - It is the platform's own visibility. API 30+: the IME source's visibility, set when a show or hide is
 *   applied, not when an animation ends (item 16) and regardless of the inset's size (item 18). Below 30: it
 *   re-reads the visible insets that the dispatched insets can carry stale (item 17).
 * - Pre-draw runs on every traversal, after insets are dispatched, so each state the window drew is seen.
 *   Deciding in the listener (not via Compose state and an effect) means a short "visible" cannot be lost
 *   between two recompositions (item 18).
 * It only reads IME state and never shows the IME (item 14). [log] gets a line per change (booleans only).
 */
@Composable
internal fun ImeHideClosesSearch(
    tracker: ImeCloseTracker,
    queryBlank: () -> Boolean,
    onClose: () -> Unit,
    log: (String) -> Unit,
) {
    val view = LocalView.current
    val currentQueryBlank by rememberUpdatedState(queryBlank)
    val currentOnClose by rememberUpdatedState(onClose)
    val currentLog by rememberUpdatedState(log)
    DisposableEffect(view, tracker) {
        var last: Boolean? = null

        fun observe() {
            // No root insets (view not attached yet / any more): no reading, so no false hide edge.
            val visible = rootImeVisible(view) ?: return
            val blank = currentQueryBlank()
            val drag = tracker.dragMarked
            val change = tracker.onIme(visible, queryBlank = blank)
            if (visible != last || change != ImeChange.None) {
                currentLog("ime visible=$visible source=rootInsets -> $change (queryBlank=$blank drag=$drag)")
            }
            last = visible
            if (change == ImeChange.HiddenClose) currentOnClose()
        }
        observe()
        val listener =
            ViewTreeObserver.OnPreDrawListener {
                observe()
                true
            }
        view.viewTreeObserver.addOnPreDrawListener(listener)
        onDispose { view.viewTreeObserver.removeOnPreDrawListener(listener) }
    }
}

private fun rootImeVisible(view: View): Boolean? =
    ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())
