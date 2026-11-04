package app.strata.launcher.core.theme.store

import app.strata.launcher.core.theme.contrast.ContrastStatus

/**
 * App-private theme files under `filesDir/themes/` (THEME_SYSTEM §11.1). Names are relative paths such as
 * `user/<id>.strata-theme.json`, `lkg.strata-theme.json` or `quarantine/<name>`; implementations never
 * build paths from anything but these names.
 *
 * The Android implementation (`:core:data`) uses `AtomicFile`. Contract: [write] is atomic (a reader sees
 * the old or the new bytes, never a mix, even if the process dies); every method may throw
 * [java.io.IOException], which callers treat as "unreadable".
 */
interface ThemeFileStore {
    fun read(name: String): ByteArray?

    /** Like [read] with another cap: fonts (4 MiB) and images (16 MiB) are larger than a theme file (§10.3.3). */
    fun readMedia(
        name: String,
        maxBytes: Int,
    ): ByteArray?

    fun write(
        name: String,
        bytes: ByteArray,
    )

    fun delete(name: String): Boolean

    /** File names directly inside [dir] (e.g. `user`), without the directory prefix. */
    fun list(dir: String): List<String>

    /** Atomic rename; replaces [to]. */
    fun move(
        from: String,
        to: String,
    )
}

/**
 * The index of THEME_SYSTEM §11.2 (a Proto DataStore `theme_index.pb` on Android). A cache over the files:
 * [read] returns null when it is missing or corrupt, and the library rebuilds it from `themes/user/`.
 */
interface ThemeIndexStore {
    fun read(): ThemeIndex?

    fun write(index: ThemeIndex)
}

/** Read-only built-in presets (the `.strata-theme.json` files under `assets/themes/` in the APK). */
interface BuiltInThemeSource {
    /** File names of the built-in presets. */
    fun names(): List<String>

    fun read(name: String): ByteArray?
}

enum class ThemeOrigin { BUILTIN, USER, IMPORTED }

/**
 * `Entry` of §11.2, plus [causedProblem] for the crash-loop guard's "Caused a problem — not applied" mark and
 * [hidden] for built-ins hidden from the picker (§11.3; a `BUILTIN` entry exists only to carry that flag).
 */
data class ThemeIndexEntry(
    val id: String,
    val name: String,
    val origin: ThemeOrigin,
    val fileSha256: String = "",
    val createdAt: Long = 0,
    val modifiedAt: Long = 0,
    val lastAppliedAt: Long = 0,
    val contrastStatus: ContrastStatus? = null,
    val needsAppUpdate: Boolean = false,
    val causedProblem: Boolean = false,
    val hidden: Boolean = false,
)

/**
 * `ThemeIndex` of §11.2. [applyCrashCount] counts starts that found [applyInProgress] still set inside the
 * 5-minute window (§12 step 4 needs two; the spec's field list has no counter, so one is added here).
 */
data class ThemeIndex(
    val entries: List<ThemeIndexEntry> = emptyList(),
    val activeThemeId: String = "",
    val lastKnownGoodId: String = "",
    val applyInProgress: Boolean = false,
    val applyStartedAt: Long = 0,
    val applyCrashCount: Int = 0,
) {
    fun entry(id: String): ThemeIndexEntry? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun withEntry(e: ThemeIndexEntry): ThemeIndex =
        copy(
            entries =
                entries.filterNot { it.id.equals(e.id, ignoreCase = true) } + e,
        )
}
