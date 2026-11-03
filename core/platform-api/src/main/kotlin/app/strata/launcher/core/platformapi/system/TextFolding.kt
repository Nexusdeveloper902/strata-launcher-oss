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

    /** FR-HOME-03: `AlarmClock.ACTION_SHOW_ALARMS` (the clock app). */
    fun showAlarms(): LaunchResult

    /** FR-HOME-03: `ACTION_VIEW` on the calendar provider's time URI for [atMillis]. */
    fun openCalendar(atMillis: Long): LaunchResult

    /** Home menu "Wallpaper" (FR-GEST-02): the system wallpaper picker (`ACTION_SET_WALLPAPER`). */
    fun wallpaperPicker(): LaunchResult

    /** FR-GEST-03: the system accessibility settings, where the lock service is turned on. */
    fun accessibilitySettings(): LaunchResult
}
