package app.strata.launcher.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.home.HomeIntents

/**
 * The app icon in any launcher (`MAIN` + `LAUNCHER`, D-008), window-less (FB-019). `Theme.NoDisplay`: it never adds a
 * window. In `onCreate` it starts [HomeActivity] with [HomeIntents.ACTION_ENTRY] (explicit, in home's own task) and
 * finishes, reading nothing: no settings store, no disk, no wait. Home decides: a first run shows onboarding as its
 * first layer, and because it was opened from the icon, Back on Welcome returns to the launcher. The old route (a
 * normal-window activity that started home and finished without drawing) made the window manager wait for a focused
 * window and could report "isn't responding".
 *
 * Only the action and category of the incoming intent matter; extras are never read (ARCHITECTURE §13).
 */
class EntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(homeIntent(this))
        finish()
    }

    companion object {
        /** Home as an app: explicit, action only, in home's own task (D-008). */
        fun homeIntent(context: Context): Intent =
            Intent(HomeIntents.ACTION_ENTRY)
                .setClass(context, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
