package app.strata.launcher.core.platformapi.system

import app.strata.launcher.core.platformapi.apps.LaunchResult

/**
 * Platform text services for search (ARCHITECTURE §3, §8 step 4). Steps 1–3 of `fold` are pure Kotlin
 * in `:core:search` (`SearchFold`), so only the ICU transliteration sits behind this interface.
 * Implemented in `:platform:system` with `android.icu.text.Transliterator` (API 29+).
 */
interface TextFolding {
    /** "Any-Latin; Latin-ASCII" of [s], or null when unavailable (API < 29) or on failure. */
    fun transliterate(s: String): String?
}

/**
 * Hand-offs to other apps (ARCHITECTURE §3 `SystemIntents`). Each start is wrapped: a missing handler gives
 * `LaunchError.NOT_FOUND`, never an exception.
 */
interface SystemIntents {
    /** True when an activity resolves `market://search?q=` (FR-SEARCH-07: the row is hidden otherwise). */
    fun canSearchStore(): Boolean

    /** Starts `ACTION_VIEW market://search?q=<query>`. Strata itself makes no network request (NFR-PRIV-01). */
    fun storeSearch(query: String): LaunchResult

    /**
     * FR-THEME-25: `ACTION_VIEW market://details?id=<packageName>`, built from the package name only. A name that is not
     * a valid package name is refused (`NOT_FOUND`). Strata itself fetches nothing (NFR-PRIV-01).
     */
    fun storeListing(packageName: String): LaunchResult

    /** FR-HOME-03: `AlarmClock.ACTION_SHOW_ALARMS` (the clock app). */
    fun showAlarms(): LaunchResult

    /** FR-HOME-03: `ACTION_VIEW` on the calendar provider's time URI for [atMillis]. */
    fun openCalendar(atMillis: Long): LaunchResult

    /** Home menu "Wallpaper" (FR-GEST-02): the system wallpaper picker (`ACTION_SET_WALLPAPER`). */
    fun wallpaperPicker(): LaunchResult

    /** FR-GEST-03: the system accessibility settings, where the lock service is turned on. */
    fun accessibilitySettings(): LaunchResult

    /**
     * Share sheet for a `content:` URI from [SharedFiles] (FR-THEME-23): `ACTION_SEND` with `EXTRA_STREAM`, [mimeType]
     * and `FLAG_GRANT_READ_URI_PERMISSION`, in a chooser titled [title].
     */
    fun share(
        contentUri: String,
        mimeType: String,
        title: String,
    ): LaunchResult

    /**
     * FR-THEME-34 / ANDROID_PLATFORM §12: hands an `content:` URI with an image MIME type from our `FileProvider` to the system
     * wallpaper activity (`getCropAndSetWallpaperIntent`, with a read grant), falling back to `ACTION_ATTACH_DATA`.
     * Never calls `WallpaperManager.set*` (no `SET_WALLPAPER`, D-009).
     */
    fun setWallpaper(contentUri: String): WallpaperHandoff
}
