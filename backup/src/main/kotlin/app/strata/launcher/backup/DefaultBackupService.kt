package app.strata.launcher.backup

import app.strata.launcher.core.common.Clock
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppOverride
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.WidgetPlacement
import app.strata.launcher.core.model.WidgetPlacementState
import app.strata.launcher.core.model.WidgetProviderRef
import app.strata.launcher.core.platformapi.apps.AppCatalog
import app.strata.launcher.core.platformapi.apps.LaunchLogRepository
import app.strata.launcher.core.platformapi.apps.ProfileSource
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
import app.strata.launcher.core.platformapi.settings.SettingsRepository
import app.strata.launcher.core.platformapi.system.DocumentIO
import app.strata.launcher.core.platformapi.system.DocumentRead
import app.strata.launcher.core.theme.store.StoredThemeFile
import app.strata.launcher.core.theme.store.ThemeBackupStore
import app.strata.launcher.core.theme.store.ThemeRestoreTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * [BackupService] of ARCHITECTURE §12 (FR-BACKUP-01…04). Every operation runs on [io] under one mutex.
 *
 * **Restore transaction** (§12.3 step 3, NFR-REL-02). (a) The current state is written to `restore/undo.json`; (b) the
 * resolved target to `restore/journal.json` (the commit point); (c) `strata.db` rows (one Room transaction), the
 * exported settings and the theme files are written; (d) the journal is deleted. A process killed during (c) finds the
 * journal at the next start and writes the target again ([onProcessStart]), so the state is all-old (killed before (b))
 * or all-new, never mixed (T-BACKUP-10). If (c) fails, the snapshot is written back the same way.
 *
 * **Privacy** (FR-APPS-10, P-5, NFR-PRIV-04): an entry is exported only when its user serial is the main profile's,
 * or a work profile's with the opt-in. Every other serial (private space, a profile type that could not be read, a
 * profile that is gone) is dropped: fail closed.
 */
class DefaultBackupService(
    private val documents: DocumentIO,
    private val apps: BackupAppStore,
    private val settings: SettingsRepository,
    private val themes: ThemeBackupStore,
    private val profiles: ProfileSource,
    private val catalog: AppCatalog,
    private val launchLog: LaunchLogRepository,
    private val files: RestoreFileStore,
    private val marker: InstallMarker,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    private val logger: Logger,
    private val appVersion: String,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** How long [export]/[preview] wait for the first profile list. */
    private val profileTimeoutMs: Long = 5_000,
) : BackupService {
    private val mutex = Mutex()
    private val undo = MutableStateFlow(false)
    override val undoAvailable: StateFlow<Boolean> = undo.asStateFlow()

    private class Prepared(
        val document: BackupDocument,
        val preview: RestorePreview,
    )

    /** Validated files waiting for "Replace current setup"; at most [MAX_PREPARED] (oldest dropped). */
    private val prepared = LinkedHashMap<String, Prepared>()

    override fun suggestedFileName(): String =
        // Not `LocalDate.ofInstant` (Java 9; on Android only from API 31).
        BackupFormat.fileName(
            Instant
                .ofEpochMilli(clock.nowMillis())
                .atZone(zone())
                .toLocalDate(),
        )

    // ---- start -----------------------------------------------------------------------------------------

    /** What [onProcessStart] found. */
    data class StartReport(
        val journalReplayed: Boolean,
        val restoreDetected: Boolean,
    )

    /**
     * Once per process, before the user can start a restore (ARCHITECTURE §12.3 steps 5–6): replays an interrupted
     * restore, deletes the last process's undo snapshot, and detects an Auto Backup restore or device transfer by
     * comparing `settings.install_id` with `noBackupFilesDir/install_marker`. After a detected restore the catalog is
     * reconciled (pending rows apply or start their 30 days) and the theme files are validated again (§11.4). Widget IDs
     * are checked by the widget area at home's `onStart` (remap or placeholder, FR-WIDGET-04).
     */
    suspend fun onProcessStart(): StartReport =
        withContext(io) {
            mutex.withLock {
                startReport?.let { return@withLock it }
                val journal = replayJournal()
                if (journal == Journal.NOT_APPLIED) {
                    // The journal could not be applied (damaged, too large, or the replay failed): the state may be
                    // mixed, so the snapshot of the state before stays and "Undo restore" is offered.
                    undo.value = safe("read undo") { files.read(UNDO, RestoreStateCodec.MAX_BYTES) }
                        ?.let(RestoreStateCodec::decode) != null
                } else {
                    safe("delete undo") { files.delete(UNDO) }
                    undo.value = false
                }
                StartReport(journal == Journal.REPLAYED, detectRestore()).also { startReport = it }
            }
        }

    /** Set once [onProcessStart] ran; every operation runs it first, whichever screen started the process. */
    @Volatile private var startReport: StartReport? = null

    private suspend fun ensureStarted() {
        if (startReport == null) onProcessStart()
    }

    private enum class Journal { NONE, REPLAYED, NOT_APPLIED }

    private suspend fun replayJournal(): Journal {
        val bytes = safe("read journal") { files.read(JOURNAL, RestoreStateCodec.MAX_BYTES) }
        if (bytes == null) {
            if (safe("check journal") { files.exists(JOURNAL) } != true) return Journal.NONE
            logger.w(TAG, "restore journal too large; discarded")
            safe("delete journal") { files.delete(JOURNAL) }
            return Journal.NOT_APPLIED
        }
        val state = RestoreStateCodec.decode(bytes)
        if (state == null) {
            logger.w(TAG, "restore journal unreadable; discarded")
            safe("delete journal") { files.delete(JOURNAL) }
            return Journal.NOT_APPLIED
        }
        logger.w(TAG, "interrupted restore found; writing it again")
        return try {
            write(state)
            files.delete(JOURNAL)
            Journal.REPLAYED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Kept: the next start tries again.
            logger.e(TAG, "restore replay failed", e)
            Journal.NOT_APPLIED
        }
    }

    private suspend fun detectRestore(): Boolean {
        val id = settings.settings.first().installId
        val stored = safe("read marker") { marker.read() }
        if (id.isNotEmpty() && id == stored) return false
        // A non-empty install id with a missing or different marker: the data came from a restore or a transfer.
        // A first start has no marker either; the same steps are then harmless no-ops.
        val detected = id.isNotEmpty()
        if (detected) {
            logger.i(TAG, "restore detected: post-restore steps")
            catalog.requestReconcile()
            safe("revalidate themes") { themes.revalidateStored() }
        }
        val fresh = newId()
        safe("rewrite install id") {
            settings.update { it.copy(installId = fresh) }
            marker.write(fresh)
        }
        return detected
    }

    // ---- export ----------------------------------------------------------------------------------------

    override suspend fun export(
        uri: String,
        options: ExportOptions,
    ): Outcome<ExportReport, BackupError> =
        withContext(io) {
            ensureStarted()
            mutex.withLock {
                guarded {
                    val kinds = profileKinds() ?: return@guarded Outcome.Err(BackupError.NotReady)
                    val doc = exportDocument(kinds, options)
                    val bytes = BackupCodec.encode(doc)
                    // The file must restore again: same limits as the validator (like the theme bundle export).
                    if (bytes.size > BackupFormat.MAX_BYTES || BackupCodec.decode(bytes) !is Outcome.Ok) {
                        return@guarded Outcome.Err(BackupError.TooLarge)
                    }
                    if (!documents.write(uri, bytes)) return@guarded Outcome.Err(BackupError.WriteFailed)
                    Outcome.Ok(ExportReport(countsOf(doc)))
                }
            }
        }

    /** Serial → profile for main and work. Null until the profiles are known. */
    private suspend fun profileKinds(): Map<Long, BackupProfile>? {
        val list =
            withTimeoutOrNull(profileTimeoutMs) {
                profiles.profiles.first { l -> l.any { it.kind == ProfileKind.MAIN } }
            } ?: return null
        return list
            .mapNotNull { p ->
                when (p.kind) {
                    ProfileKind.MAIN -> p.serial to BackupProfile.MAIN

                    ProfileKind.WORK -> p.serial to BackupProfile.WORK

                    // Private space and unknown types never map to anything (fail closed).
                    ProfileKind.PRIVATE, ProfileKind.OTHER -> null
                }
            }.toMap()
    }

    private suspend fun exportDocument(
        kinds: Map<Long, BackupProfile>,
        options: ExportOptions,
    ): BackupDocument {
        fun profileOf(serial: Long): BackupProfile? =
            kinds[serial]?.takeIf { it == BackupProfile.MAIN || options.workProfile }

        val data = apps.read()
        val s = settings.settings.first()
        val t = themes.backupData()
        val appEntries =
            data.overrides.mapNotNull { o ->
                val p = profileOf(o.key.userSerial) ?: return@mapNotNull null
                if (!BackupFormat.isComponent(o.key.component)) return@mapNotNull null
                if (!o.hidden && o.customLabel == null && o.icon == null) return@mapNotNull null
                BackupApp(o.key.component, p, o.hidden, o.customLabel, o.icon)
            }
        val favorites =
            data.favorites.mapNotNull { k ->
                val p = profileOf(k.userSerial) ?: return@mapNotNull null
                BackupRef(k.component, p).takeIf { BackupFormat.isComponent(k.component) }
            }
        val widgets =
            data.widgets.mapNotNull { w ->
                val p = profileOf(w.provider.userSerial) ?: return@mapNotNull null
                BackupWidget(w.provider.component, p, w.heightStep, w.replaceClock)
                    .takeIf { BackupFormat.isComponent(w.provider.component) }
            }
        val log =
            if (options.launchLog) {
                launchLog.dayCounts().mapNotNull { d ->
                    val p = profileOf(d.key.userSerial) ?: return@mapNotNull null
                    BackupLaunchDay(
                        d.key.component,
                        p,
                        d.day,
                        d.count,
                    ).takeIf { BackupFormat.isComponent(d.key.component) }
                }
            } else {
                emptyList()
            }
        val active = t.activeId.lowercase().takeIf { BackupFormat.THEME_ID.matches(it) }
        val embedded =
            t.themes.map {
                if (it.needsAppUpdate) {
                    EmbeddedTheme.Newer(it.id, it.id, it.tree)
                } else {
                    EmbeddedTheme.Valid(it.id, it.id, it.tree)
                }
            }
        return BackupDocument(
            createdAt = RFC3339.format(Instant.ofEpochMilli(clock.nowMillis())),
            appVersion = appVersion,
            workProfile = options.workProfile,
            launchLogIncluded = options.launchLog,
            settings = s,
            settingsCount = SettingsCodec.KEYS.size,
            apps = appEntries,
            favorites = favorites,
            themes =
                BackupThemes(
                    active = active,
                    builtinRefs = listOfNotNull(active).filter { it in t.builtInIds },
                    hiddenBuiltins = t.hiddenBuiltins,
                    embedded = embedded,
                ),
            widgets = widgets,
            launchLog = log,
        )
    }

    // ---- preview ---------------------------------------------------------------------------------------

    override suspend fun preview(uri: String): Outcome<RestorePreview, BackupError> =
        withContext(io) {
            ensureStarted()
            val bytes =
                when (val r = documents.read(uri, BackupFormat.MAX_BYTES)) {
                    is DocumentRead.Ok -> r.bytes

                    DocumentRead.TooLarge -> return@withContext Outcome.Err(BackupError.TooLarge)

                    DocumentRead.UnsupportedScheme, DocumentRead.Failed -> return@withContext Outcome.Err(
                        BackupError.Unreadable,
                    )
                }
            mutex.withLock {
                guarded {
                    val decoded =
                        when (val d = BackupCodec.decode(bytes)) {
                            is Outcome.Ok -> d.value
                            is Outcome.Err -> return@guarded d
                        }
                    val kinds = profileKinds() ?: return@guarded Outcome.Err(BackupError.NotReady)
                    val token = newId()
                    val preview = previewOf(token, decoded, kinds)
                    synchronized(prepared) {
                        prepared[token] = Prepared(decoded.document, preview)
                        while (prepared.size > MAX_PREPARED) prepared.remove(prepared.keys.first())
                    }
                    Outcome.Ok(preview)
                }
            }
        }

    private fun previewOf(
        token: String,
        d: BackupCodec.Decoded,
        kinds: Map<Long, BackupProfile>,
    ): RestorePreview {
        val doc = d.document
        val installed = installedKeys()
        val serials = serialsOf(kinds)
        val workSkipped =
            if (serials[BackupProfile.WORK] == null) {
                doc.apps.count { it.profile == BackupProfile.WORK } +
                    doc.favorites.count { it.profile == BackupProfile.WORK } +
                    doc.widgets.count { it.profile == BackupProfile.WORK }
            } else {
                0
            }

        val loaded = loadedSerials()

        fun missing(
            component: String,
            profile: BackupProfile,
        ): Boolean {
            val serial = serials[profile] ?: return false
            return serial in loaded && AppKey(component, serial) !in installed
        }
        val missing =
            (
                doc.apps.filter { missing(it.component, it.profile) }.map { it.packageName } +
                    doc.favorites.filter { missing(it.component, it.profile) }.map { it.packageName }
            ).distinct().sorted()
        val themes = doc.themes?.embedded.orEmpty()
        return RestorePreview(
            token = token,
            counts = countsOf(doc),
            missingApps = missing,
            widgetsToReAdd = doc.widgets.count { serials[it.profile] != null },
            newerSettingsIgnored = d.stats.unknownKeys,
            droppedEntries = d.stats.dropped,
            excludedThemes = themes.filterIsInstance<EmbeddedTheme.Invalid>().map { it.name },
            needsUpdateThemes = themes.filterIsInstance<EmbeddedTheme.Newer>().map { it.name },
            workSkipped = workSkipped,
            createdAt = doc.createdAt,
        )
    }

    override fun discard(token: String) {
        // Not under the mutex (main-safe, no suspension): the map itself is synchronised.
        synchronized(prepared) { prepared.remove(token) }
    }

    // ---- restore and undo ------------------------------------------------------------------------------

    override suspend fun restore(token: String): Outcome<RestorePreview, BackupError> =
        withContext(io + NonCancellable) {
            ensureStarted()
            mutex.withLock {
                val p =
                    synchronized(prepared) { prepared.remove(token) }
                        ?: return@withLock Outcome.Err(BackupError.Expired)
                val kinds = profileKinds() ?: return@withLock Outcome.Err(BackupError.NotReady)
                val before =
                    try {
                        snapshot(kinds)
                    } catch (e: Exception) {
                        logger.e(TAG, "snapshot failed; nothing restored", e)
                        return@withLock Outcome.Err(BackupError.RestoreFailed)
                    }
                val target = targetOf(p.document, kinds)
                val undoBytes = RestoreStateCodec.encode(before)
                try {
                    // (a) the snapshot, then (b) the commit point.
                    files.write(UNDO, undoBytes)
                    files.write(JOURNAL, RestoreStateCodec.encode(target))
                } catch (e: Exception) {
                    logger.e(TAG, "restore files not written; nothing restored", e)
                    safe("cleanup") { files.delete(JOURNAL) }
                    return@withLock Outcome.Err(BackupError.RestoreFailed)
                }
                try {
                    write(target)
                    files.delete(JOURNAL)
                    undo.value = true
                    Outcome.Ok(p.preview)
                } catch (e: Exception) {
                    logger.e(TAG, "restore failed; rolling back", e)
                    rolledBack(undoBytes, before)
                } catch (e: OutOfMemoryError) {
                    logger.e(TAG, "restore ran out of memory; rolling back", e)
                    rolledBack(undoBytes, before)
                }
            }
        }

    /**
     * Writes [before] back after a failed step. The journal on disk must never describe something other than what a
     * replay should produce: if it cannot be rewritten as the snapshot, the restore's journal is deleted (otherwise the
     * next start would apply the restore that was just reported as failed), and [before] is written anyway.
     */
    private suspend fun rolledBack(
        undoBytes: ByteArray,
        before: RestoreState,
    ): Outcome<RestorePreview, BackupError> {
        val journalIsSnapshot =
            try {
                files.write(JOURNAL, undoBytes)
                true
            } catch (e: Exception) {
                logger.e(TAG, "rollback journal not written", e)
                safe("delete restore journal") { files.delete(JOURNAL) }
                false
            }
        return try {
            write(before)
            safe("delete journal") { files.delete(JOURNAL) }
            safe("delete undo") { files.delete(UNDO) }
            undo.value = false
            Outcome.Err(BackupError.RestoreFailed)
        } catch (e: Exception) {
            // Not fully back. With the journal = the snapshot, the next start finishes it; "Undo restore" can try now.
            logger.e(TAG, "rollback failed (journal is snapshot: $journalIsSnapshot)", e)
            undo.value = true
            Outcome.Err(BackupError.RestoreIncomplete)
        }
    }

    override suspend fun undoRestore(): Outcome<Unit, BackupError> =
        withContext(io + NonCancellable) {
            ensureStarted()
            mutex.withLock {
                if (!undo.value) return@withLock Outcome.Err(BackupError.NothingToUndo)
                val bytes =
                    safe("read undo") { files.read(UNDO, RestoreStateCodec.MAX_BYTES) }
                        ?: return@withLock Outcome.Err(BackupError.NothingToUndo).also { undo.value = false }
                val state =
                    RestoreStateCodec.decode(bytes)
                        ?: return@withLock Outcome.Err(BackupError.NothingToUndo).also { undo.value = false }
                try {
                    files.write(JOURNAL, bytes)
                    write(state)
                    files.delete(JOURNAL)
                    files.delete(UNDO)
                    undo.value = false
                    Outcome.Ok(Unit)
                } catch (e: Exception) {
                    // The journal stays and is written again at the next start.
                    logger.e(TAG, "undo failed", e)
                    Outcome.Err(BackupError.RestoreFailed)
                }
            }
        }

    /**
     * The current state (the undo snapshot, §12.3 (a)): exact for the main and work profiles; never the launch log.
     * Fail closed (P-5): rows of any other serial (private space, unknown profile types, gone profiles) are left out,
     * so no such component ever reaches `restore/`; the restore replaces the tables as a whole, so they are not kept.
     */
    private suspend fun snapshot(kinds: Map<Long, BackupProfile>): RestoreState {
        val t = themes.backupData()
        val data = apps.read()
        return RestoreState(
            settings = settings.settings.first(),
            apps =
                AppData(
                    overrides = data.overrides.filter { it.key.userSerial in kinds },
                    favorites = data.favorites.filter { it.userSerial in kinds },
                    widgets = data.widgets.filter { it.provider.userSerial in kinds },
                ),
            themes = ThemeRestoreTarget(t.themes, t.activeId, t.hiddenBuiltins, exact = true, knownIds = t.storedIds),
        )
    }

    /** Steps (b)–(e): rows in one transaction, then the exported settings, then the theme files. Throws on failure. */
    private suspend fun write(state: RestoreState) {
        apps.replace(state.apps)
        settings.update { SettingsCodec.copyExported(from = state.settings, into = it) }
        themes.restoreThemes(state.themes)
        catalog.requestReconcile()
    }

    /** The backup resolved for this device (§12.3 steps 3–4). */
    private fun targetOf(
        doc: BackupDocument,
        kinds: Map<Long, BackupProfile>,
    ): RestoreState {
        val serials = serialsOf(kinds)
        val installed = installedKeys()
        val loaded = loadedSerials()
        val now = clock.nowMillis()

        fun keyOf(
            component: String,
            profile: BackupProfile,
        ): AppKey? = serials[profile]?.let { AppKey(component, it) }

        // Like the catalog's own marking: only a profile whose apps were loaded can say an app is missing. A paused
        // work profile's entries stay active and are marked by the reconcile once it is loaded.
        fun since(key: AppKey): Long? = if (key in installed || key.userSerial !in loaded) null else now
        val overrides = LinkedHashMap<AppKey, AppOverride>()
        for (a in doc.apps) {
            val key = keyOf(a.component, a.profile) ?: continue
            if (!a.hidden && a.label == null && a.icon == null) continue
            overrides[key] = AppOverride(key, a.hidden, a.label, a.icon, since(key))
        }
        val favorites = doc.favorites.mapNotNull { keyOf(it.component, it.profile) }.distinct()
        // FR-BACKUP-03: a favourite of an app that is not installed is kept pending with the same 30 days as a record.
        for (k in favorites) {
            if (k !in installed && k.userSerial in loaded &&
                k !in overrides
            ) {
                overrides[k] = AppOverride(k, missingSince = now)
            }
        }
        val widgets =
            doc.widgets
                .mapNotNull { w ->
                    val serial = serials[w.profile] ?: return@mapNotNull null
                    WidgetPlacement(
                        // No ID is bound yet: a negative placeholder ID until Re-add binds one (FR-WIDGET-04).
                        appWidgetId = RESTORED_WIDGET_ID,
                        provider = WidgetProviderRef(w.provider, serial),
                        heightStep = w.heightStep,
                        replaceClock = w.replaceClock,
                        state = WidgetPlacementState.NEEDS_REBIND,
                    )
                }.take(1)
        val t = doc.themes
        val themeTarget =
            if (t == null) {
                ThemeRestoreTarget(emptyList(), activeId = null, hiddenBuiltins = null, exact = false)
            } else {
                val files =
                    t.embedded.mapNotNull {
                        when (it) {
                            is EmbeddedTheme.Valid -> StoredThemeFile(it.id, it.tree)
                            is EmbeddedTheme.Newer -> StoredThemeFile(it.id, it.tree, needsAppUpdate = true)
                            is EmbeddedTheme.Invalid -> null
                        }
                    }
                ThemeRestoreTarget(files, activeId = t.active, hiddenBuiltins = t.hiddenBuiltins, exact = false)
            }
        return RestoreState(
            // A file without `settings` restores the catalogue defaults ("Replace current setup").
            settings = doc.settings ?: Settings.DEFAULT,
            apps = AppData(overrides.values.toList(), favorites, widgets),
            themes = themeTarget,
        )
    }

    private fun serialsOf(kinds: Map<Long, BackupProfile>): Map<BackupProfile, Long> {
        val out = HashMap<BackupProfile, Long>()
        // `main` → the main user; `work` → the first managed profile (lowest serial, stable order).
        for ((serial, p) in kinds.entries.sortedBy { it.key }) out.putIfAbsent(p, serial)
        return out
    }

    /** Serials whose apps the catalog holds now: an available profile with at least one app listed. */
    private fun loadedSerials(): Set<Long> {
        val available =
            profiles.profiles.value
                .filter { it.available }
                .mapTo(HashSet()) { it.serial }
        return catalog.catalog.value.entries
            .mapTo(HashSet()) { it.key.userSerial }
            .filterTo(HashSet()) { it in available }
    }

    private fun installedKeys(): Set<AppKey> =
        catalog.catalog.value.entries
            .filter { it.profileKind == ProfileKind.MAIN || it.profileKind == ProfileKind.WORK }
            .mapTo(HashSet()) { it.key }

    private fun countsOf(doc: BackupDocument): BackupCounts =
        BackupCounts(
            settings = doc.settingsCount,
            favorites = doc.favorites.size,
            hidden = doc.apps.count { it.hidden },
            renames = doc.apps.count { it.label != null },
            iconOverrides = doc.apps.count { it.icon != null },
            themes = doc.themes?.embedded?.count { it !is EmbeddedTheme.Invalid } ?: 0,
            widgets = doc.widgets.size,
            workEntries =
                doc.apps.count { it.profile == BackupProfile.WORK } +
                    doc.favorites.count { it.profile == BackupProfile.WORK } +
                    doc.widgets.count { it.profile == BackupProfile.WORK },
            launchLogDays = doc.launchLog.size,
        )

    /** Non-I/O failures and OOM become typed errors; nothing escapes into the caller's scope (M5 review lesson). */
    private inline fun <T> guarded(block: () -> Outcome<T, BackupError>): Outcome<T, BackupError> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "backup operation failed", e)
            Outcome.Err(BackupError.Unreadable)
        } catch (e: OutOfMemoryError) {
            logger.e(TAG, "backup operation ran out of memory", e)
            Outcome.Err(BackupError.TooLarge)
        }

    private inline fun <T> safe(
        what: String,
        block: () -> T,
    ): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "$what failed", e)
            null
        }

    companion object {
        private const val TAG = "Backup"
        const val UNDO = "undo.json"
        const val JOURNAL = "journal.json"
        const val MAX_PREPARED = 2

        /** Placement ID of a restored widget before Re-add (no ID is bound; never a real host ID). */
        const val RESTORED_WIDGET_ID = -1

        private val RFC3339 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    }
}
