package app.strata.launcher.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * FR-SET-03: the system privacy dashboard's "why does Strata use this?" link (`VIEW_PERMISSION_USAGE`). Exported but
 * guarded by `START_VIEW_PERMISSION_USAGE` (only the system can start it, T-SEC-04). No extra of the incoming intent
 * is read (ARCHITECTURE §13): it opens Strata's own Privacy & permissions page and finishes.
 */
class PermissionUsageActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .setAction(SettingsActivity.ACTION_PRIVACY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}
