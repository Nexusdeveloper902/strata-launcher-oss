package app.strata.launcher.platform.system

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.icu.text.Transliterator
import android.net.Uri
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
import app.strata.launcher.core.platformapi.system.WallpaperHandoff

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

    override fun storeListing(packageName: String): LaunchResult =
        listingIntent(packageName)?.let(::start) ?: Outcome.Err(LaunchError.NOT_FOUND)

    override fun showAlarms(): LaunchResult = start(Intent(AlarmClock.ACTION_SHOW_ALARMS))

    override fun openCalendar(atMillis: Long): LaunchResult = start(calendarIntent(atMillis))

    // Not a chooser: Intent.createChooser never throws ActivityNotFoundException, so "no handler" could not be reported.
    override fun wallpaperPicker(): LaunchResult = start(Intent(Intent.ACTION_SET_WALLPAPER))

    override fun accessibilitySettings(): LaunchResult = start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    override fun uninstallStrata(): LaunchResult =
        start(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", appContext.packageName, null)))

    override fun share(
        contentUri: String,
        mimeType: String,
        title: String,
    ): LaunchResult {
        val uri = contentUri.toUri()
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return Outcome.Err(LaunchError.SECURITY)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType(mimeType)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData carries the grant through the chooser to the chosen app.
        send.clipData = ClipData.newRawUri(null, uri)
        return start(Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    override fun setWallpaper(contentUri: String): WallpaperHandoff {
        val uri = contentUri.toUri()
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return WallpaperHandoff.NO_HANDLER
        val crop =
            try {
                WallpaperManager.getInstance(appContext).getCropAndSetWallpaperIntent(uri)
            } catch (e: IllegalArgumentException) {
                null // no cropper for this URI, or not content:/image (ANDROID_PLATFORM §12)
            } catch (e: RuntimeException) {
                null
            }
        if (crop != null) {
            crop.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            crop.clipData = ClipData.newRawUri(null, uri)
            if (start(crop) is Outcome.Ok) return WallpaperHandoff.CROP_AND_SET
        }
        // Started directly (not wrapped in a chooser), so a missing handler is detectable; the system resolver lets the
        // user pick when several apps can set wallpapers.
        val attach =
            Intent(Intent.ACTION_ATTACH_DATA)
                .setDataAndType(uri, IMAGE_TYPE)
                .putExtra("mimeType", IMAGE_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (start(attach) is Outcome.Ok) WallpaperHandoff.ATTACH_DATA else WallpaperHandoff.NO_HANDLER
    }

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
        private const val IMAGE_TYPE = "image/*"

        /** `market://search?q=<query>` (FR-SEARCH-07). */
        fun storeIntent(query: String): Intent =
            Intent(Intent.ACTION_VIEW, "market://search".toUri().buildUpon().appendQueryParameter("q", query).build())

        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

        /** FR-THEME-25: `market://details?id=<pkg>`, or null when [packageName] is not a package name. */
        fun listingIntent(packageName: String): Intent? =
            if (packageName.length <= 255 && PACKAGE_NAME.matches(packageName)) {
                Intent(
                    Intent.ACTION_VIEW,
                    "market://details".toUri().buildUpon().appendQueryParameter("id", packageName).build(),
                )
            } else {
                null
            }

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
