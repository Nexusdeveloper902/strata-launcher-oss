package app.strata.launcher.core.platformapi.backup

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppOverride
import app.strata.launcher.core.model.WidgetPlacement
import kotlinx.coroutines.flow.StateFlow

/** What the user chose on the export sheet (ARCHITECTURE §12.2). Both are off by default (FR-BACKUP-01). */
data class ExportOptions(
    val workProfile: Boolean = false,
    val launchLog: Boolean = false,
)

/** Counts shown after an export and in the restore preview (ARCHITECTURE §12.3 step 2, J-11). */
data class BackupCounts(
    val settings: Int = 0,
    val favorites: Int = 0,
    val hidden: Int = 0,
    val renames: Int = 0,
    val iconOverrides: Int = 0,
    val themes: Int = 0,
    val widgets: Int = 0,
    val workEntries: Int = 0,
    val launchLogDays: Int = 0,
)

data class ExportReport(
    val counts: BackupCounts,
)

/**
 * The validated file, before anything is written (ARCHITECTURE §12.3 step 2). [token] names the prepared restore held by
 * the service until [BackupService.restore] or [BackupService.discard].
 */
data class RestorePreview(
    val token: String,
    val counts: BackupCounts,
    /** Package names of apps in the file that are not installed (kept as pending, FR-BACKUP-03), sorted. */
    val missingApps: List<String>,
    /** Widget placements that come back as "Re-add" placeholders (FR-WIDGET-04). */
    val widgetsToReAdd: Int,
    /** Unknown keys of a newer minor version ("N newer settings will be ignored"). */
    val newerSettingsIgnored: Int,
    /** Entries dropped as invalid (bad component, private or unknown profile, duplicates, out-of-range values). */
    val droppedEntries: Int,
    /** Names of embedded themes that failed validation and are not restored (T-BACKUP-09). */
    val excludedThemes: List<String>,
    /** Names of embedded themes with a newer `schemaVersion`: kept on disk, "Needs app update". */
    val needsUpdateThemes: List<String>,
    /** Work-profile entries that cannot be restored because this phone has no work profile. */
    val workSkipped: Int,
    /** `createdAt` of the file, as written (informational). */
    val createdAt: String?,
)

/** Typed failures of the backup flows (ARCHITECTURE §11). None carries file content. */
sealed interface BackupError {
    /** Valid JSON, but not a `strata-backup` document ("This file isn't a Strata backup"). */
    data object NotABackup : BackupError

    /** "This backup is damaged (line L, column C)". Line 0 = no position (bad encoding). */
    data class Damaged(
        val line: Int,
        val column: Int,
    ) : BackupError

    /** Over 8 MiB or over a structural limit ("This backup is too large"). */
    data object TooLarge : BackupError

    /** `formatVersion` > 1: "Made with a newer version of Strata Launcher". */
    data object NewerVersion : BackupError

    /** The document could not be opened or read (provider gone, permission revoked). */
    data object Unreadable : BackupError

    /** The export could not be written. */
    data object WriteFailed : BackupError

    /** The profiles are not known yet, so nothing could be mapped. */
    data object NotReady : BackupError

    /** The prepared restore is gone (cancelled, replaced or process restarted). */
    data object Expired : BackupError

    /** A restore step failed; the previous state was put back. */
    data object RestoreFailed : BackupError

    /** "Undo restore" is no longer available (next start, or nothing restored). */
    data object NothingToUndo : BackupError
}

/**
 * The backup flows of ARCHITECTURE §3/§12 (FR-BACKUP-01…04). Bound in `:app` to the `:backup` implementation, so
 * `:feature:settings` never sees the codec. Every method is main-safe and never throws for bad data.
 */
interface BackupService {
    /** `strata-<yyyy-MM-dd>.strata-backup.json` for today (local date). */
    fun suggestedFileName(): String

    /** Writes the current state to [uri] (a document from `ACTION_CREATE_DOCUMENT`). */
    suspend fun export(
        uri: String,
        options: ExportOptions,
    ): Outcome<ExportReport, BackupError>

    /** Reads and validates [uri]; nothing is written. */
    suspend fun preview(uri: String): Outcome<RestorePreview, BackupError>

    /** "Replace current setup": snapshot, then replace (atomic; rolled back on failure). */
    suspend fun restore(token: String): Outcome<RestorePreview, BackupError>

    fun discard(token: String)

    /** Puts back the state from before the last restore of this process. */
    suspend fun undoRestore(): Outcome<Unit, BackupError>

    /** True while [undoRestore] can run (until the next process start). */
    val undoAvailable: StateFlow<Boolean>
}

/** Everything of `strata.db` a backup carries (overrides, favourites, widget placements), read or written at once. */
data class AppData(
    val overrides: List<AppOverride> = emptyList(),
    val favorites: List<AppKey> = emptyList(),
    val widgets: List<WidgetPlacement> = emptyList(),
)

/** Port over `strata.db` for the restore transaction (ARCHITECTURE §12.3 step 3b, 3e). Implemented in `:core:data`. */
interface BackupAppStore {
    suspend fun read(): AppData

    /** Replaces overrides, favourites and widget placements in **one** Room transaction. Throws on failure. */
    suspend fun replace(data: AppData)
}

/** One day bucket of the launch log (ARCHITECTURE §12.1 `launchLog`): never timestamps. */
data class LaunchDayCount(
    val key: AppKey,
    /** `yyyy-MM-dd` (UTC). */
    val day: String,
    val count: Int,
)

/**
 * Files under `filesDir/restore/` (undo snapshot and restore journal, ARCHITECTURE §4 "Pre-restore snapshot"). Writes
 * are atomic (`AtomicFile`); names are fixed constants of the backup service, never built from input.
 */
interface RestoreFileStore {
    fun read(
        name: String,
        maxBytes: Int,
    ): ByteArray?

    fun write(
        name: String,
        bytes: ByteArray,
    )

    fun delete(name: String)
}

/** `noBackupFilesDir/install_marker` (ARCHITECTURE §12.3 step 6): never part of any backup. */
interface InstallMarker {
    fun read(): String?

    fun write(value: String)
}
