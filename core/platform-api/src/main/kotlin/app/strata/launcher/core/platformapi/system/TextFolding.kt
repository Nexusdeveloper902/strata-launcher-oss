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
 * Hand-offs to other apps (ARCHITECTURE §3 `SystemIntents`). M2 needs the store search of FR-SEARCH-07;
 * the clock/calendar/home-settings intents arrive with their milestones.
 */
interface SystemIntents {
    /** True when an activity resolves `market://search?q=` (FR-SEARCH-07: the row is hidden otherwise). */
    fun canSearchStore(): Boolean

    /** Starts `ACTION_VIEW market://search?q=<query>`. Strata itself makes no network request (NFR-PRIV-01). */
    fun storeSearch(query: String): LaunchResult
}
