package app.strata.launcher.feature.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Home gesture thresholds of INTERACTION_SPEC §4.1 (FR-GEST-01). Pure, in dp, so T-GEST-01 checks them on the JVM.
 */
object HomeGestures {
    /** Minimum upward travel. */
    const val SWIPE_MIN_DP = 48f

    /** A fling this fast upward opens search even with less travel. */
    const val FLING_MIN_DP_PER_S = 1_000f

    /** The swipe must be within this angle of vertical. */
    const val MAX_ANGLE_DEG = 30.0

    /**
     * True when a gesture with total movement ([dxDp], [dyDp]) (dy < 0 = up) and release velocity [vyDpPerS]
     * (< 0 = up) is a swipe up.
     */
    fun isSwipeUp(
        dxDp: Float,
        dyDp: Float,
        vyDpPerS: Float,
    ): Boolean {
        if (dyDp >= 0f) return false
        val angle = Math.toDegrees(atan2(abs(dxDp).toDouble(), abs(dyDp).toDouble()))
        if (angle > MAX_ANGLE_DEG) return false
        return -dyDp >= SWIPE_MIN_DP || -vyDpPerS >= FLING_MIN_DP_PER_S
    }
}

/**
 * Swipe up anywhere on home (FR-GEST-01). Runs in the Main pass after the children, so a gesture a child consumed
 * (the overflowing favourites block scrolling, the index scrubbing, a long-press menu) never counts.
 */
internal fun Modifier.swipeUp(onSwipeUp: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            val start = down.position
            var last = start
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (change.isConsumed) return@awaitEachGesture
                tracker.addPointerInputChange(change)
                last = change.position
                if (!change.pressed) break
            }
            val delta: Offset = last - start
            val velocity = tracker.calculateVelocity()
            val dpx = density
            if (HomeGestures.isSwipeUp(delta.x / dpx, delta.y / dpx, velocity.y / dpx)) onSwipeUp()
        }
    }

/**
 * Long-press and double-tap on empty home (FR-GEST-02, FR-GEST-03), for a background layer under the content: only
 * touches no child took arrive here. A move beyond touch slop cancels both, without consuming, so the swipe-up
 * detector still sees it. Double-tap is listened for only when [onDoubleTap] is set (INTERACTION_SPEC §4.2 rule 6).
 */
internal fun Modifier.emptySpaceGestures(
    onLongPress: () -> Unit,
    onDoubleTap: (() -> Unit)?,
): Modifier =
    pointerInput(onDoubleTap != null) {
        var lastTapUp = 0L
        var lastTapAt = Offset.Zero
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            val slop = viewConfiguration.touchSlop
            var outcome = Outcome.PENDING
            val finished =
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull
                        if (change.isConsumed || (change.position - down.position).getDistance() > slop) {
                            outcome = Outcome.MOVED
                            return@withTimeoutOrNull
                        }
                        if (!change.pressed) {
                            outcome = Outcome.TAP
                            if (onDoubleTap != null) change.consume()
                            return@withTimeoutOrNull
                        }
                    }
                }
            if (finished == null && outcome == Outcome.PENDING) {
                onLongPress()
                // The rest of this gesture belongs to the menu: consume it so nothing else reacts.
                while (true) {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) break
                }
                return@awaitEachGesture
            }
            if (outcome == Outcome.TAP && onDoubleTap != null) {
                val now = down.uptimeMillis
                val near =
                    (down.position - lastTapAt).getDistance() < viewConfiguration.touchSlop * DOUBLE_TAP_SLOP_FACTOR
                if (now - lastTapUp <= viewConfiguration.doubleTapTimeoutMillis && near) {
                    lastTapUp = 0L
                    onDoubleTap()
                } else {
                    lastTapUp = now
                    lastTapAt = down.position
                }
            }
        }
    }

private enum class Outcome { PENDING, TAP, MOVED }

private const val DOUBLE_TAP_SLOP_FACTOR = 4f
