package app.strata.launcher.platform.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * The host view of one widget (ANDROID_PLATFORM §7, INTERACTION_SPEC §4.2 rule 5). It owns every gesture that starts
 * inside it, so home's swipe up and long-press never see them (T-GEST-02): [dispatchTouchEvent] always reports the
 * event handled. A press held still for the long-press timeout calls [longPressListener] (the widget menu) and steals
 * the gesture from the widget; a child that starts scrolling (`requestDisallowInterceptTouchEvent`) or a move beyond
 * the touch slop cancels it, so no menu opens while the widget scrolls.
 */
class StrataWidgetHostView(
    context: Context,
) : AppWidgetHostView(context) {
    var longPressListener: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private val firePress =
        Runnable {
            if (!isAttachedToWindow) return@Runnable
            longPressed = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            longPressListener?.invoke()
        }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        super.dispatchTouchEvent(ev)
        return true
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return longPressed
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return true
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept) removeCallbacks(firePress)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(firePress)
        super.onDetachedFromWindow()
    }

    private fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                downX = ev.x
                downY = ev.y
                removeCallbacks(firePress)
                if (longPressListener != null) {
                    postDelayed(firePress, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (hypot(ev.x - downX, ev.y - downY) > slop) removeCallbacks(firePress)
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(firePress)
            }
        }
    }
}
