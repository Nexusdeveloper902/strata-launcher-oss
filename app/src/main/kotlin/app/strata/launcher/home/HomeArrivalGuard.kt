package app.strata.launcher.home

/**
 * Keeps an arrival on home (the system Home gesture or button pressed from another app) from acting as a gesture or a
 * Home press made on home itself (FB-008, FR-HOME-05, FR-GEST-01, INTERACTION_SPEC §6).
 *
 * - A singleTask home receives `onNewIntent(MAIN/HOME)` both when Home is pressed while home is in front and when the
 *   user returns from another app. Only the first is a Home press in the sense of INTERACTION_SPEC §6 ("Home key while
 *   in front"); the second must never open search through the FR-HOME-05 setting.
 * - A pointer stream whose `ACTION_DOWN` happened before home was resumed (and, once reported, focused) began outside
 *   home, for example the tail of the system Home gesture handed to the home window as the transition ends. It is
 *   dropped whole, so neither the swipe-up detector nor a tap sees it.
 *
 * All times are `SystemClock.uptimeMillis()`, the clock of `MotionEvent.getDownTime()`. Main thread only.
 */
class HomeArrivalGuard {
    private var stoppedSinceResume = true
    private var resumed = false
    private var interactiveSince = Long.MAX_VALUE
    private var droppingStream = false

    fun onResume(nowUptime: Long) {
        resumed = true
        stoppedSinceResume = false
        interactiveSince = nowUptime
    }

    fun onPause() {
        resumed = false
    }

    fun onStop() {
        stoppedSinceResume = true
    }

    /** Window focus counts too: a focus gain after resume moves the start of the interactive period forward. */
    fun onWindowFocusChanged(
        hasFocus: Boolean,
        nowUptime: Long,
    ) {
        if (hasFocus && resumed) interactiveSince = maxOf(interactiveSince, nowUptime)
    }

    /**
     * True when a Home intent is a press made while home was already in front. The activity is paused (not stopped)
     * around `onNewIntent` in that case; arriving from another app it was stopped, and the system marks the intent
     * [broughtToFront].
     */
    fun isHomePressInFront(broughtToFront: Boolean): Boolean = !stoppedSinceResume && !broughtToFront

    /**
     * Decides whether one touch event reaches the content. [isDown] is `ACTION_DOWN`; the decision made at the down
     * holds for the whole stream until the next down.
     */
    fun acceptTouch(
        isDown: Boolean,
        downTime: Long,
    ): Boolean {
        if (isDown) droppingStream = !resumed || downTime < interactiveSince
        return !droppingStream
    }
}
