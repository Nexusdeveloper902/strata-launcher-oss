package app.strata.launcher.core.theme.store

import app.strata.launcher.core.json.JsonObject

/** One stored user theme as a backup carries it: the file's JSON tree, verbatim (unknown keys included). */
data class StoredThemeFile(
    val id: String,
    val tree: JsonObject,
    /** A newer `schemaVersion`: written back untouched and listed as "Needs app update" (THEME_SYSTEM §10.5). */
    val needsAppUpdate: Boolean = false,
)

/** The theme part of a backup or of the pre-restore snapshot (ARCHITECTURE §12.1 `themes`). */
data class ThemeBackupData(
    val activeId: String,
    /** Built-ins hidden from the picker (§11.3). */
    val hiddenBuiltins: Set<String>,
    val themes: List<StoredThemeFile>,
    /** Ids of the built-in presets and Strata Default (referenced, never embedded). */
    val builtInIds: Set<String>,
)

/**
 * What a restore writes (ARCHITECTURE §12.3 step 3d). A restore adds or replaces [themes] (same id → Replace) and
 * keeps the others; an undo ([exact]) also deletes stored themes that are not in [themes]. [activeId] is applied when
 * it can be loaded, else the current theme is kept. [hiddenBuiltins] null = unchanged.
 */
data class ThemeRestoreTarget(
    val themes: List<StoredThemeFile>,
    val activeId: String?,
    val hiddenBuiltins: Set<String>?,
    val exact: Boolean,
)

/**
 * Theme side of the backup flows (THEME_SYSTEM §11.4, ARCHITECTURE §12). Implemented by the theme repository on its
 * serial dispatcher. [restoreThemes] throws [java.io.IOException] when a file cannot be written; the caller rolls back.
 */
interface ThemeBackupStore {
    suspend fun backupData(): ThemeBackupData

    suspend fun restoreThemes(target: ThemeRestoreTarget)

    /**
     * Re-validates every stored file after an Auto Backup restore (§11.4): invalid files move to `themes/quarantine/`,
     * newer ones are marked "Needs app update". Returns the number quarantined.
     */
    suspend fun revalidateStored(): Int
}
