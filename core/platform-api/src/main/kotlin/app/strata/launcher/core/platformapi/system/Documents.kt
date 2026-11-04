package app.strata.launcher.core.platformapi.system

/** Result of a bounded document read. */
sealed interface DocumentRead {
    class Ok(val bytes: ByteArray) : DocumentRead

    /** More than the cap: nothing beyond cap + 1 bytes was read. */
    data object TooLarge : DocumentRead

    /** Not a `content:` URI (ARCHITECTURE §13, T-SEC-01): refused before anything is opened. */
    data object UnsupportedScheme : DocumentRead

    /** Provider revoked, file deleted, permission missing (ANDROID_PLATFORM §16 failure row). */
    data object Failed : DocumentRead
}

/**
 * `DocumentIO` of ARCHITECTURE §3: reads and writes Storage Access Framework documents (`content:` URIs only), off the
 * main thread. Implemented in `:platform:system` over `ContentResolver`; no storage permission (ANDROID_PLATFORM §16).
 */
interface DocumentIO {
    /** Reads at most [maxBytes]; a longer document gives [DocumentRead.TooLarge]. */
    suspend fun read(
        uri: String,
        maxBytes: Int,
    ): DocumentRead

    /** Truncates and writes [bytes]; false on any failure. */
    suspend fun write(
        uri: String,
        bytes: ByteArray,
    ): Boolean
}

/**
 * Temporary copies under `cacheDir/share/` exposed through our non-exported `FileProvider` (THEME_SYSTEM §10.1,
 * ANDROID_PLATFORM §12, §22). Copies are purged after 24 h ([purgeExpired], called at process start).
 */
interface SharedFiles {
    /** Writes [bytes] as [fileName] and returns its `content:` URI, or null when the write failed. */
    suspend fun publish(
        fileName: String,
        bytes: ByteArray,
    ): String?

    /** Deletes copies older than 24 h. */
    suspend fun purgeExpired()
}

/** How a theme wallpaper was handed to the system (FR-THEME-34). */
enum class WallpaperHandoff {
    /** `WallpaperManager.getCropAndSetWallpaperIntent` started. */
    CROP_AND_SET,

    /** No crop activity: `ACTION_ATTACH_DATA` with an image MIME type started instead. */
    ATTACH_DATA,

    /** Neither resolves: "No app on this device can set wallpapers". */
    NO_HANDLER,
}
