package app.strata.launcher.feature.search

import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** What an IME visibility change means for the search layer (item 8 / 17 of docs/impl-notes/M2.md). */
internal enum class ImeChange {
    /** No visible → hidden edge. */
    None,

    /** The IME hid with an empty query and not because of a results drag: this is the Back that closes search. */
    HiddenClose,

    /** The IME hid, but search stays (query not empty, or a results drag hid it). */
    HiddenKeep,
}

/**
 * Edge detector for "one Back closes search" with an empty query. Feed it every observed IME state; the first
 * observation counts, so a layer that opens while the IME is (or is still reported) up closes on the next hide.
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

/** Which signal [rememberImeShownForClose] reads on this device (for the debug close log). */
internal val IME_CLOSE_SOURCE: String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "imeAnimationTarget" else "rootInsets"

/**
 * The IME state the empty-query close keys off. API 30+: Compose's `imeAnimationTarget` (item 16: it moves as
 * soon as the hide is applied, unlike `isImeVisible`). Below 30: the root view's insets (item 17).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberImeShownForClose(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        return WindowInsets.imeAnimationTarget.getBottom(LocalDensity.current) > 0
    }
    // API < 30 (item 17): ViewRootImpl dispatches insets *before* it stores the new visible insets, and
    // androidx's compat IME inset falls back to those visible insets under edge-to-edge, so the dispatched
    // insets (and every Compose IME value) can keep saying "IME up" after the hide until some later dispatch.
    // `ViewCompat.getRootWindowInsets` re-reads them. Re-read on pre-draw: it runs on every traversal, after
    // the visible insets are stored, even one that lays nothing out (a visible-insets-only change does not
    // trigger a layout, so a global-layout listener could miss it). The guard writes only real changes, so the
    // recomposition this causes cannot loop.
    val view = LocalView.current
    var rootVisible by remember(view) { mutableStateOf(rootImeVisible(view)) }
    DisposableEffect(view) {
        val listener =
            ViewTreeObserver.OnPreDrawListener {
                val now = rootImeVisible(view)
                if (now != rootVisible) rootVisible = now
                true
            }
        view.viewTreeObserver.addOnPreDrawListener(listener)
        onDispose { view.viewTreeObserver.removeOnPreDrawListener(listener) }
    }
    return rootVisible
}

private fun rootImeVisible(view: View): Boolean =
    ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
