package app.strata.launcher.pin

import android.app.Activity
import android.os.Bundle

/**
 * Target of `CONFIRM_PIN_SHORTCUT` / `CONFIRM_PIN_APPWIDGET` (ANDROID_PLATFORM §6, §22). v1 declines every pin request
 * (FR-APPS-08: pinned shortcuts as favourites are v1.x): it finishes in `onCreate` with no UI and no state, and never
 * reads the intent. In particular the `PinItemRequest` extra is not touched: it carries a binder from the caller, and an
 * exported activity must not make a binder call to an untrusted process on the home's main thread (a hung binder
 * would freeze home). Not calling `accept()` is the decline (ANDROID_PLATFORM §6); a forged intent is handled the same
 * way (T-SEC-02).
 */
class PinRequestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
