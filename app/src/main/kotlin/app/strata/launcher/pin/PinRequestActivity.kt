package app.strata.launcher.pin

import android.app.Activity
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.widget.Toast
import app.strata.launcher.R

/**
 * Target of `CONFIRM_PIN_SHORTCUT` / `CONFIRM_PIN_APPWIDGET` (ANDROID_PLATFORM §6, §22). v1 declines every pin request
 * (FR-APPS-08: pinned shortcuts as favourites are v1.x): `accept()` is never called, so nothing is pinned, and nothing
 * is stored. An intent without a valid pin request (forged, T-SEC-02) finishes at once with no UI and no state.
 * Only the request object from `LauncherApps.getPinItemRequest` is read; other extras never are (ARCHITECTURE §13).
 */
class PinRequestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && isValidPinRequest(intent)) {
            Toast.makeText(this, R.string.pin_request_declined, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun isValidPinRequest(intent: Intent?): Boolean {
        if (intent?.action !in PIN_ACTIONS) return false
        return try {
            getSystemService(LauncherApps::class.java)?.getPinItemRequest(intent)?.isValid == true
        } catch (e: RuntimeException) {
            // A malformed extra (BadParcelableException, ClassCastException…) is a forged request.
            false
        }
    }

    private companion object {
        val PIN_ACTIONS = setOf(LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT, LauncherApps.ACTION_CONFIRM_PIN_APPWIDGET)
    }
}
