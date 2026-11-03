package app.strata.launcher.platform.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.icu.text.Transliterator
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.platformapi.apps.LaunchError
import app.strata.launcher.core.platformapi.apps.LaunchResult
import app.strata.launcher.core.platformapi.system.SystemIntents
import app.strata.launcher.core.platformapi.system.TextFolding

/**
 * [TextFolding] with `android.icu.text.Transliterator` "Any-Latin; Latin-ASCII" (API 29+; ARCHITECTURE §8
 * step 4). Returns null on API 26–28. ICU transliterators are not thread-safe, so calls are serialised;
 * the index calls it only at build time, off main.
 */
class AndroidTextFolding : TextFolding {
    private val transliterator: Any? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) create() else null
    }

    override fun transliterate(s: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return transliterateQ(s)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun transliterateQ(s: String): String? {
        val t = transliterator as? Transliterator ?: return null
        return try {
            synchronized(t) { t.transliterate(s) }
        } catch (e: RuntimeException) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun create(): Transliterator? =
        try {
            Transliterator.getInstance(ID)
        } catch (e: IllegalArgumentException) {
            null
        }

    private companion object {
        const val ID = "Any-Latin; Latin-ASCII"
    }
}

/**
 * [SystemIntents] on the framework. The store search resolves `ACTION_VIEW market://search?q=` (visible
 * through the manifest `<queries>` entry for the `market` scheme) and starts it; Strata opens no socket.
 */
class AndroidSystemIntents(context: Context) : SystemIntents {
    private val appContext = context.applicationContext

    override fun canSearchStore(): Boolean =
        try {
            appContext.packageManager.resolveActivity(storeIntent(""), PackageManager.MATCH_DEFAULT_ONLY) != null
        } catch (e: RuntimeException) {
            false
        }

    override fun storeSearch(query: String): LaunchResult = start(storeIntent(query))

    override fun showAlarms(): LaunchResult = start(Intent(AlarmClock.ACTION_SHOW_ALARMS))

    override fun openCalendar(atMillis: Long): LaunchResult = start(calendarIntent(atMillis))

    // Not a chooser: Intent.createChooser never throws ActivityNotFoundException, so "no handler" could not be reported.
    override fun wallpaperPicker(): LaunchResult = start(Intent(Intent.ACTION_SET_WALLPAPER))

    override fun accessibilitySettings(): LaunchResult = start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun start(intent: Intent): LaunchResult =
        try {
            appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Outcome.Ok(Unit)
        } catch (e: ActivityNotFoundException) {
            Outcome.Err(LaunchError.NOT_FOUND)
        } catch (e: SecurityException) {
            Outcome.Err(LaunchError.SECURITY)
        }

    companion object {
        /** `market://search?q=<query>` (FR-SEARCH-07). */
        fun storeIntent(query: String): Intent =
            Intent(Intent.ACTION_VIEW, "market://search".toUri().buildUpon().appendQueryParameter("q", query).build())

        /** FR-HOME-03: `content://com.android.calendar/time/<ms>` (`CalendarContract.CONTENT_URI` + `time`). */
        fun calendarIntent(atMillis: Long): Intent =
            Intent(
                Intent.ACTION_VIEW,
                CalendarContract.CONTENT_URI
                    .buildUpon()
                    .appendPath("time")
                    .appendPath(atMillis.toString())
                    .build(),
            )
    }
}
