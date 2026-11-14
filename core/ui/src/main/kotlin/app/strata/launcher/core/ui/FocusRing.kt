package app.strata.launcher.core.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * FR-A11Y-04, VISUAL_SPEC §11: every focused element shows a visible ring in the theme's `accent` (contrast ≥ 3:1 is
 * checked by T-A11Y-11). Every focusable element puts [focusRing] before its `clickable`/`toggleable`; Material
 * controls get it through the wrappers in `StrataControls.kt`.
 */
object FocusRingSemantics {
    /** True while the ring is drawn; read by the keyboard walk test (T-A11Y-06). */
    val Shown = SemanticsPropertyKey<Boolean>("StrataFocusRing")
}

val FOCUS_RING_WIDTH = 2.dp
private val DEFAULT_RING_SHAPE = RoundedCornerShape(8.dp)

/**
 * Draws the focus ring around this element while the focus target that follows in the chain (or a child target
 * directly under it) is focused.
 */
fun Modifier.focusRing(shape: Shape = DEFAULT_RING_SHAPE): Modifier = this then FocusRingElement(shape)

private data class FocusRingElement(
    val shape: Shape,
) : ModifierNodeElement<FocusRingNode>() {
    override fun create() = FocusRingNode(shape)

    override fun update(node: FocusRingNode) {
        node.shape = shape
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "focusRing"
    }
}

private class FocusRingNode(
    var shape: Shape,
) : Modifier.Node(),
    FocusEventModifierNode,
    DrawModifierNode,
    SemanticsModifierNode,
    CompositionLocalConsumerModifierNode {
    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused != focused) {
            focused = focusState.isFocused
            invalidateDraw()
            invalidateSemantics()
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (focused) drawRing(currentValueOf(LocalStrataColors).accent, shape)
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        if (focused) this[FocusRingSemantics.Shown] = true
    }
}

/** A stroke fully inside the bounds, so a parent's clip never hides it. */
internal fun ContentDrawScope.drawRing(
    color: androidx.compose.ui.graphics.Color,
    shape: Shape,
) {
    val w = FOCUS_RING_WIDTH.toPx()
    if (size.width <= w || size.height <= w) return
    val outline = shape.createOutline(Size(size.width - w, size.height - w), layoutDirection, this)
    translate(w / 2, w / 2) { drawOutline(outline, color, style = Stroke(w)) }
}

/**
 * While [blocked], nothing in this subtree takes keyboard focus: a hidden layer (home under the list, search or edit
 * favourites; `alpha(0)` + cleared semantics) must not be a Tab stop. The subtree is a focus group that focus cannot
 * enter, and focus that is already inside when it becomes blocked is cleared, so the next Tab enters the top layer.
 */
fun Modifier.blockFocus(blocked: Boolean): Modifier =
    if (!blocked) {
        this
    } else {
        this
            .then(ClearFocusInsideElement)
            .focusProperties {
                canFocus = false
                onEnter = { cancelFocusChange() }
            }.focusGroup()
    }

private object ClearFocusInsideElement : ModifierNodeElement<ClearFocusInsideNode>() {
    override fun create() = ClearFocusInsideNode()

    override fun update(node: ClearFocusInsideNode) = Unit

    override fun hashCode(): Int = javaClass.hashCode()

    override fun equals(other: Any?): Boolean = other === this

    override fun InspectorInfo.inspectableProperties() {
        name = "blockFocus"
    }
}

private class ClearFocusInsideNode :
    Modifier.Node(),
    FocusEventModifierNode,
    CompositionLocalConsumerModifierNode {
    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.hasFocus) clear()
    }

    override fun onAttach() {
        // Blocked while focus was already inside (e.g. the window's first focus on the clock): move it out.
        sideEffect { clear() }
    }

    private fun clear() {
        coroutineScope.launch {
            if (isAttached) currentValueOf(LocalFocusManager).clearFocus(force = true)
        }
    }
}
