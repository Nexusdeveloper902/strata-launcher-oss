package app.strata.launcher.testing.fakes

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.platformapi.apps.LaunchError
import app.strata.launcher.core.platformapi.apps.LaunchResult
import app.strata.launcher.core.platformapi.system.SystemIntents
import app.strata.launcher.core.platformapi.system.TextFolding
import app.strata.launcher.core.platformapi.system.WallpaperHandoff

/**
 * [TextFolding] from a fixed table (pure JVM; no ICU on this module's runtime classpath, T-CI-02).
 * Tests that need real ICU4J transliteration use the test-only ICU adapter in `:core:search` tests.
 */
class FakeTextFolding(
    private val table: Map<String, String> = emptyMap(),
) : TextFolding {
    val calls = mutableListOf<String>()

    override fun transliterate(s: String): String? {
        calls += s
        return table[s]
    }
}

/** Records hand-offs; [storeHandler] = whether a `market:` activity resolves (T-SEARCH-15). */
class FakeSystemIntents(
    var storeHandler: Boolean = true,
) : SystemIntents {
    val storeSearches = mutableListOf<String>()

    override fun canSearchStore(): Boolean = storeHandler

    override fun storeSearch(query: String): LaunchResult {
        if (!storeHandler) return Outcome.Err(LaunchError.NOT_FOUND)
        storeSearches += query
        return Outcome.Ok(Unit)
    }

    /** Hand-offs started, by name ("alarms", "calendar", "wallpaper", "accessibility"). */
    val started = mutableListOf<String>()

    /** Names whose handler is missing (T-HOME-06: NotFound). */
    val missing = mutableSetOf<String>()

    private fun start(name: String): LaunchResult {
        if (name in missing) return Outcome.Err(LaunchError.NOT_FOUND)
        started += name
        return Outcome.Ok(Unit)
    }

    /** Package names of every store listing opened (FR-THEME-25). */
    val storeListings = mutableListOf<String>()

    override fun storeListing(packageName: String): LaunchResult {
        if (!storeHandler) return Outcome.Err(LaunchError.NOT_FOUND)
        storeListings += packageName
        return Outcome.Ok(Unit)
    }

    override fun showAlarms(): LaunchResult = start(ALARMS)

    override fun openCalendar(atMillis: Long): LaunchResult = start(CALENDAR)

    override fun wallpaperPicker(): LaunchResult = start(WALLPAPER)

    override fun accessibilitySettings(): LaunchResult = start(ACCESSIBILITY)

    override fun uninstallStrata(): LaunchResult = start(UNINSTALL_STRATA)

    /** (uri, mime) of every share sheet started. */
    val shares = mutableListOf<Pair<String, String>>()

    override fun share(
        contentUri: String,
        mimeType: String,
        title: String,
    ): LaunchResult {
        if (SHARE in missing) return Outcome.Err(LaunchError.NOT_FOUND)
        shares += contentUri to mimeType
        return Outcome.Ok(Unit)
    }

    /** Whether a crop activity / an ATTACH_DATA handler resolves (T-THEME-42). */
    var cropHandler = true
    var attachHandler = true

    /** (kind, uri) of every wallpaper hand-off started: kind is [CROP] or [ATTACH]. */
    val wallpaperHandoffs = mutableListOf<Pair<String, String>>()

    override fun setWallpaper(contentUri: String): WallpaperHandoff =
        when {
            cropHandler -> {
                wallpaperHandoffs += CROP to contentUri
                WallpaperHandoff.CROP_AND_SET
            }

            attachHandler -> {
                wallpaperHandoffs += ATTACH to contentUri
                WallpaperHandoff.ATTACH_DATA
            }

            else -> {
                WallpaperHandoff.NO_HANDLER
            }
        }

    companion object {
        const val ALARMS = "alarms"
        const val CALENDAR = "calendar"
        const val WALLPAPER = "wallpaper"
        const val ACCESSIBILITY = "accessibility"
        const val UNINSTALL_STRATA = "uninstall_strata"
        const val SHARE = "share"
        const val CROP = "crop"
        const val ATTACH = "attach"
    }
}
