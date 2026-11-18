package app.strata.launcher.core.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged

/**
 * The text fields of one window that hold focus right now. A field registers itself with [imeField]; the window's
 * stray-keyboard guard ([StrayImeGuard]) never hides the IME while one is focused, whatever screen state says.
 */
class ImeFields {
    var focused: Int by mutableIntStateOf(0)
        private set

    internal fun add() {
        focused++
    }

    internal fun remove() {
        if (focused > 0) focused--
    }
}

/** The window's [ImeFields]; null outside a host that guards the keyboard (the field then registers nowhere). */
val LocalImeFields = staticCompositionLocalOf<ImeFields?> { null }

/**
 * Marks a text field: while it has focus the soft keyboard belongs to it. Every text field that can sit in a window
 * with a [StrayImeGuard] (home: search, rename, the favourites and onboarding pickers) must carry it.
 */
fun Modifier.imeField(): Modifier =
    composed {
        val fields = LocalImeFields.current
        var counted by remember { mutableStateOf(false) }
        DisposableEffect(fields) {
            onDispose {
                if (counted) fields?.remove()
                counted = false
            }
        }
        onFocusChanged { state ->
            val now = state.isFocused
            if (fields == null || now == counted) return@onFocusChanged
            counted = now
            if (now) fields.add() else fields.remove()
        }
    }

/**
 * Hides a keyboard that is up although nothing can type into it: no focused [imeField] and the screen state does not
 * expect one ([allowed]). A keyboard over a focused field is never hidden, so a show completing before (or after)
 * the screen state catches up cannot be closed by this guard. [hide] is called once per stray appearance.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StrayImeGuard(
    allowed: Boolean,
    fields: ImeFields,
    imeVisible: Boolean = WindowInsets.isImeVisible,
    hide: () -> Unit,
) {
    val currentHide by rememberUpdatedState(hide)
    val fieldFocused = fields.focused > 0
    LaunchedEffect(imeVisible, allowed, fieldFocused) {
        if (StrayIme.shouldHide(imeVisible, allowed, fieldFocused)) currentHide()
    }
}

/** The decision of [StrayImeGuard], pure for tests. */
object StrayIme {
    fun shouldHide(
        imeVisible: Boolean,
        allowed: Boolean,
        fieldFocused: Boolean,
    ): Boolean = imeVisible && !allowed && !fieldFocused
}
