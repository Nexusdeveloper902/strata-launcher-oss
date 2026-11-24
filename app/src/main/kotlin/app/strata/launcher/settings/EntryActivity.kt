package app.strata.launcher.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.strata.launcher.appGraph
import app.strata.launcher.home.HomeActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app icon in any launcher (`MAIN` + `LAUNCHER`, D-008), window-less (FB-019). `Theme.NoDisplay`: it never adds a
 * window, routes in `onCreate` and finishes there, so the window manager never waits for a window of ours that will
 * not draw (the old route started home from a window it never drew, and the system could report "isn't responding").
 *
 * - `onboarding_done` true: an explicit intent to [HomeActivity] in its own task, as before.
 * - false: [SettingsActivity] in this (settings) task with the same `MAIN` + `LAUNCHER` intent, so it hosts onboarding
 *   exactly as before (Back leaves to the launcher that opened it).
 * - Not known within [READ_BUDGET_MS] (a cold settings store): the same hand-over to [SettingsActivity], whose router
 *   reads the flag without a time limit. Never a guess.
 *
 * The flag is read on the store's own thread; the main thread only waits, at most [READ_BUDGET_MS] (no disk access on
 * main). Only the action and category of the incoming intent matter; extras are never read (ARCHITECTURE §13).
 */
class EntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val done =
            runBlocking {
                withTimeoutOrNull(READ_BUDGET_MS) {
                    appGraph.settingsRepository.settings
                        .first()
                        .onboardingDone
                }
            }
        startActivity(EntryRouter.entryTarget(done).intent(this))
        finish()
    }

    companion object {
        /** A warm store answers from memory; a cold one is left to the settings router rather than blocking main. */
        const val READ_BUDGET_MS = 250L
    }
}

/** Where the window-less entry hands over (FB-019). */
enum class EntryTarget {
    HOME,

    /** [SettingsActivity] as the D-008 router (onboarding, or home once the flag is read). */
    ROUTER,
    ;

    fun intent(activity: Activity): Intent =
        when (this) {
            HOME -> {
                Intent(activity, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            ROUTER -> {
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClass(activity, SettingsActivity::class.java)
            }
        }
}
