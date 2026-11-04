package app.strata.launcher.core.theme.store

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.theme.contrast.ContrastStatus
import app.strata.launcher.core.theme.importing.ConflictChoice
import app.strata.launcher.core.theme.importing.ImportConflict
import app.strata.launcher.core.theme.importing.ImportError
import app.strata.launcher.core.theme.importing.ThemeImage
import app.strata.launcher.core.theme.model.IconPackRef
import kotlinx.coroutines.flow.StateFlow

/** The import summary of THEME_SYSTEM §10.4 step 2. Nothing is written or applied until it is committed. */
data class ImportPreview(
    /** Identifies the prepared import held in memory by the manager. */
    val token: String,
    val themeId: String,
    /** The name in the file. */
    val name: String,
    /** The name after a name collision ("Name (2)") for Add / Keep both. */
    val displayName: String,
    /** The name for Replace (the replaced theme's own name does not collide). */
    val replaceName: String,
    val author: String?,
    val description: String?,
    val conflict: ImportConflict,
    /**
     * §7.6: [ContrastStatus.AA_ANY_WALLPAPER] "Readable on any wallpaper", [ContrastStatus.AA_CURRENT_WALLPAPER]
     * "Readable on current wallpaper", [ContrastStatus.LOW_CONTRAST] "Has low-contrast colours (will be adjusted
     * while 'Enforce contrast' is on)". Computed without enforcement.
     */
    val contrast: ContrastStatus,
    /** "N unknown settings from a newer version will be kept" (§10.5). */
    val newerSettingsKept: Int,
    /** A referenced icon pack that is not installed (store button), or null. */
    val missingIconPack: IconPackRef?,
    /** User fonts the theme references that neither the bundle nor the font store has. */
    val missingFonts: Int,
    val fromBundle: Boolean,
    val hasWallpaperImage: Boolean,
    /** The theme resolved for both system dark states, for the preview thumbnail. */
    val preview: ThemePair,
)

/** Result of a committed import. */
data class ImportCommit(
    val id: String,
    /** An existing theme was replaced; [ThemeManager.undoReplace] restores it until the next start. */
    val replaced: Boolean,
    val applied: Boolean,
)

/** A delete waiting for its 5-second undo window (§11.3). */
data class DeleteTicket(
    val id: String,
    val name: String,
    /** The theme was active and [newActiveId] (last-known-good or Strata Default) was applied first. */
    val wasActive: Boolean,
    val newActiveId: String?,
    /** SHA-256 of the file when the delete started: a theme re-imported inside the window is not deleted. */
    val fileSha256: String = "",
)

/** Why an export failed. */
enum class ExportError {
    NOT_FOUND,

    /** The bundle would exceed the import limits of THEME_SYSTEM §10.3.3, so it could not be imported back. */
    TOO_LARGE,
}

/** A file for `ACTION_CREATE_DOCUMENT` or a share (§10.1). */
class ExportFile(
    val suggestedName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/**
 * Library management, import and export (THEME_SYSTEM §10, §11.3; FR-THEME-21…27). Implemented by the theme repository
 * (same serial dispatcher and library instance as [ThemeRepository]); every method is main-safe and never throws for bad
 * data. All theming is free and unlimited (FR-THEME-01): nothing here is gated or counted.
 */
interface ThemeManager {
    /** Bumped after every change to the library (save, import, delete, hide), so open lists reload. */
    val revision: StateFlow<Int>

    /** Every theme, built-ins first (hidden built-ins included, flagged). */
    suspend fun entries(): List<ThemeSummary>

    /** Validates [bytes] (sniffed, §3) and returns the summary; nothing is written. */
    suspend fun prepareImport(bytes: ByteArray): Outcome<ImportPreview, ImportError>

    /**
     * Writes the prepared import (§10.4 step 3). [choice] matters only for [ImportConflict.SAME_ID]; built-in ids are
     * always kept as a copy. [ImportConflict.IDENTICAL] writes nothing (it may still apply).
     */
    suspend fun commitImport(
        token: String,
        choice: ConflictChoice,
        apply: Boolean,
    ): Outcome<ImportCommit, ImportError>

    fun discardImport(token: String)

    /** Restores the version an import replaced (kept in memory until the next start). */
    suspend fun undoReplace(id: String): Boolean

    /** A user copy "Name (copy)" of any theme; returns its id. */
    suspend fun duplicate(id: String): String?

    /**
     * The id to edit: [id] itself for a stored theme; for a built-in (or Strata Default) a new user copy, created on the
     * first change (§11.3).
     */
    suspend fun editableCopy(id: String): String?

    /** Starts a delete: an active theme is switched away from at once; the file goes on [commitDelete]. */
    suspend fun requestDelete(id: String): DeleteTicket?

    /**
     * Undo within the 5 s window: re-applies the theme if it was active and the user has not chosen another theme
     * since (the active id is still [DeleteTicket.newActiveId]).
     */
    suspend fun undoDelete(ticket: DeleteTicket)

    /**
     * Deletes the file and entry. Returns false (and deletes nothing) when the theme changed inside the window: it was
     * re-imported or replaced (different file hash) or applied again.
     */
    suspend fun commitDelete(ticket: DeleteTicket): Boolean

    suspend fun setHidden(
        id: String,
        hidden: Boolean,
    )

    suspend fun exportJson(id: String): ExportFile?

    /** Whether the theme has user fonts or a wallpaper image to bundle ("Include fonts and wallpaper (.zip)"). */
    suspend fun hasBundleMedia(id: String): Boolean

    suspend fun exportBundle(id: String): Outcome<ExportFile, ExportError>

    /** The stored wallpaper image of a theme (FR-THEME-34 hand-off), or null. */
    suspend fun wallpaperImage(id: String): ThemeImage?
}
