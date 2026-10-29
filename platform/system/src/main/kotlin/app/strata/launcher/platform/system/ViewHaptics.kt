package app.strata.launcher.platform.system

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import app.strata.launcher.core.platformapi.system.HapticKind
import app.strata.launcher.core.platformapi.system.Haptics
import java.lang.ref.WeakReference

/**
 * [Haptics] through `View.performHapticFeedback` on the attached window (INTERACTION_SPEC §4.3). It
 * respects the system haptics setting and needs no `VIBRATE` permission. The activity attaches its
 * decor view in `onCreate` and detaches it in `onDestroy`; without a view, calls are dropped.
 */
class ViewHaptics : Haptics {
    @Volatile private var view: WeakReference<View>? = null

    fun attach(view: View) {
        this.view = WeakReference(view)
    }

    fun detach(view: View) {
        if (this.view?.get() === view) this.view = null
    }

    fun detachAll() {
        view = null
    }

    override fun tick(kind: HapticKind) {
        view?.get()?.performHapticFeedback(constant(kind, Build.VERSION.SDK_INT))
    }

    companion object {
        /** API-level mapping of INTERACTION_SPEC §4.3. */
        fun constant(
            kind: HapticKind,
            sdk: Int,
        ): Int =
            when (kind) {
                HapticKind.TICK -> {
                    if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                    } else {
                        HapticFeedbackConstants.CLOCK_TICK
                    }
                }

                HapticKind.LONG_PRESS -> {
                    HapticFeedbackConstants.LONG_PRESS
                }

                HapticKind.GESTURE_START -> {
                    if (sdk >=
                        Build.VERSION_CODES.R
                    ) {
                        HapticFeedbackConstants.GESTURE_START
                    } else {
                        HapticFeedbackConstants.VIRTUAL_KEY
                    }
                }

                HapticKind.GESTURE_END -> {
                    if (sdk >=
                        Build.VERSION_CODES.R
                    ) {
                        HapticFeedbackConstants.GESTURE_END
                    } else {
                        HapticFeedbackConstants.VIRTUAL_KEY
                    }
                }
            }
    }
}
