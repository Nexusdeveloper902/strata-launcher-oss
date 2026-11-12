package app.strata.launcher.testing.fakes

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.platformapi.backup.AppData
import app.strata.launcher.core.platformapi.backup.BackupAppStore
import app.strata.launcher.core.platformapi.backup.BackupCounts
import app.strata.launcher.core.platformapi.backup.BackupError
import app.strata.launcher.core.platformapi.backup.BackupService
import app.strata.launcher.core.platformapi.backup.ExportOptions
import app.strata.launcher.core.platformapi.backup.ExportReport
import app.strata.launcher.core.platformapi.backup.InstallMarker
import app.strata.launcher.core.platformapi.backup.RestoreFileStore
import app.strata.launcher.core.platformapi.backup.RestorePreview
import app.strata.launcher.core.theme.store.StoredThemeFile
import app.strata.launcher.core.theme.store.ThemeBackupData
import app.strata.launcher.core.theme.store.ThemeBackupStore
import app.strata.launcher.core.theme.store.ThemeRestoreTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory [BackupAppStore]; [failNextReplace] makes one replace throw (rollback tests). */
class FakeBackupAppStore(
    var data: AppData = AppData(),
) : BackupAppStore {
    var failNextReplace = false
    var replaces = 0
        private set

    override suspend fun read(): AppData = data

    override suspend fun replace(data: AppData) {
        if (failNextReplace) {
            failNextReplace = false
            throw java.io.IOException("simulated write failure")
        }
        replaces++
        this.data = data
    }
}

/** In-memory [ThemeBackupStore] with the restore rules of the real library (built-ins never written). */
class FakeThemeBackupStore(
    var activeId: String = "",
    var hiddenBuiltins: Set<String> = emptySet(),
    val stored: LinkedHashMap<String, StoredThemeFile> = LinkedHashMap(),
    val builtInIds: Set<String> = emptySet(),
) : ThemeBackupStore {
    var failNextRestore = false

    /** Stored themes [backupData] cannot carry (an unreadable file): listed only in `storedIds`. */
    val unreadable = mutableSetOf<String>()

    /** Number of further restores that fail (rollback tests). */
    var failRestores = 0

    /** Called before each theme write (kill-point tests). */
    var beforeWrite: () -> Unit = {}

    var revalidations = 0
        private set

    override suspend fun backupData(): ThemeBackupData =
        ThemeBackupData(
            activeId,
            hiddenBuiltins,
            stored.values.filterNot { it.id in unreadable },
            builtInIds,
            storedIds = stored.keys.toSet(),
        )

    override suspend fun restoreThemes(target: ThemeRestoreTarget) {
        if (failNextRestore || failRestores > 0) {
            failNextRestore = false
            if (failRestores > 0) failRestores--
            throw java.io.IOException("simulated theme write failure")
        }
        for (t in target.themes) {
            if (t.id in builtInIds) continue
            beforeWrite()
            stored[t.id] = t
        }
        if (target.exact) stored.keys.retainAll(target.themes.map { it.id }.toSet() + target.knownIds)
        target.hiddenBuiltins?.let { hiddenBuiltins = it }
        target.activeId?.let { if (it in stored || it in builtInIds) activeId = it }
    }

    override suspend fun revalidateStored(): Int {
        revalidations++
        return 0
    }
}

/** In-memory [RestoreFileStore]. */
class FakeRestoreFiles : RestoreFileStore {
    val files = linkedMapOf<String, ByteArray>()
    var failWrites = false

    /** Fails the write when it returns true for (name, how many writes of that name came before). */
    var failWrite: (String, Int) -> Boolean = { _, _ -> false }
    private val writesOf = HashMap<String, Int>()

    override fun exists(name: String): Boolean = name in files

    override fun read(
        name: String,
        maxBytes: Int,
    ): ByteArray? = files[name]?.takeIf { it.size <= maxBytes }?.copyOf()

    override fun write(
        name: String,
        bytes: ByteArray,
    ) {
        val n = writesOf.merge(name, 1, Int::plus)!! - 1
        if (failWrites || failWrite(name, n)) throw java.io.IOException("simulated")
        files[name] = bytes.copyOf()
    }

    override fun delete(name: String) {
        files.remove(name)
    }
}

class FakeInstallMarker(
    var value: String? = null,
) : InstallMarker {
    override fun read(): String? = value

    override fun write(value: String) {
        this.value = value
    }
}

/** Scripted [BackupService] for UI tests. */
class FakeBackupService : BackupService {
    var exportResult: Outcome<ExportReport, BackupError> = Outcome.Ok(ExportReport(BackupCounts()))
    var previewResult: Outcome<RestorePreview, BackupError> = Outcome.Err(BackupError.NotABackup)
    var restoreResult: Outcome<RestorePreview, BackupError>? = null
    var undoResult: Outcome<Unit, BackupError> = Outcome.Ok(Unit)
    val exports = mutableListOf<Pair<String, ExportOptions>>()
    val previews = mutableListOf<String>()
    val restores = mutableListOf<String>()
    val discarded = mutableListOf<String>()
    var undos = 0
        private set
    private val undoState = MutableStateFlow(false)
    override val undoAvailable: StateFlow<Boolean> = undoState

    override fun suggestedFileName(): String = "strata-2026-11-12.strata-backup.json"

    override suspend fun export(
        uri: String,
        options: ExportOptions,
    ): Outcome<ExportReport, BackupError> {
        exports += uri to options
        return exportResult
    }

    override suspend fun preview(uri: String): Outcome<RestorePreview, BackupError> {
        previews += uri
        return previewResult
    }

    override suspend fun restore(token: String): Outcome<RestorePreview, BackupError> {
        restores += token
        val r = restoreResult ?: previewResult
        if (r is Outcome.Ok) undoState.value = true
        return r
    }

    override fun discard(token: String) {
        discarded += token
    }

    override suspend fun undoRestore(): Outcome<Unit, BackupError> {
        undos++
        if (undoResult is Outcome.Ok) undoState.value = false
        return undoResult
    }
}
