package app.strata.launcher.core.data.theme

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.common.trace.TraceSections
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.model.AppearanceSetting
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.editor.EditorDraft
import app.strata.launcher.core.theme.editor.SeedExtractor
import app.strata.launcher.core.theme.importing.BundleCodec
import app.strata.launcher.core.theme.importing.BundleProblem
import app.strata.launcher.core.theme.importing.ConflictChoice
import app.strata.launcher.core.theme.importing.ImageCheck
import app.strata.launcher.core.theme.importing.ImageOutcome
import app.strata.launcher.core.theme.importing.ImportCandidate
import app.strata.launcher.core.theme.importing.ImportConflict
import app.strata.launcher.core.theme.importing.ImportError
import app.strata.launcher.core.theme.importing.ImportPlan
import app.strata.launcher.core.theme.importing.ThemeImage
import app.strata.launcher.core.theme.importing.ThemeImporter
import app.strata.launcher.core.theme.importing.ThemeMedia
import app.strata.launcher.core.theme.model.IconPackRef
import app.strata.launcher.core.theme.model.ImageMediaType
import app.strata.launcher.core.theme.model.PackFallback
import app.strata.launcher.core.theme.model.ThemeDocument
import app.strata.launcher.core.theme.model.WallpaperImage
import app.strata.launcher.core.theme.parse.ThemeError
import app.strata.launcher.core.theme.parse.ThemeErrorText
import app.strata.launcher.core.theme.resolve.AppearanceOverride
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.ThemeGlobals
import app.strata.launcher.core.theme.resolve.ThemeResolver
import app.strata.launcher.core.theme.store.BootResult
import app.strata.launcher.core.theme.store.BootSource
import app.strata.launcher.core.theme.store.BundleExport
import app.strata.launcher.core.theme.store.DeleteTicket
import app.strata.launcher.core.theme.store.EditTarget
import app.strata.launcher.core.theme.store.EditorSaveError
import app.strata.launcher.core.theme.store.ExportError
import app.strata.launcher.core.theme.store.ExportFile
import app.strata.launcher.core.theme.store.ImageSeedError
import app.strata.launcher.core.theme.store.ImageSeedResult
import app.strata.launcher.core.theme.store.ImportCommit
import app.strata.launcher.core.theme.store.ImportPreview
import app.strata.launcher.core.theme.store.PackNotice
import app.strata.launcher.core.theme.store.PreviewEnvironment
import app.strata.launcher.core.theme.store.ThemeBackupData
import app.strata.launcher.core.theme.store.ThemeBackupStore
import app.strata.launcher.core.theme.store.ThemeBanner
import app.strata.launcher.core.theme.store.ThemeBannerKind
import app.strata.launcher.core.theme.store.ThemeEditorStore
import app.strata.launcher.core.theme.store.ThemeFileStore
import app.strata.launcher.core.theme.store.ThemeLibrary
import app.strata.launcher.core.theme.store.ThemeManager
import app.strata.launcher.core.theme.store.ThemeOrigin
import app.strata.launcher.core.theme.store.ThemePair
import app.strata.launcher.core.theme.store.ThemeRepository
import app.strata.launcher.core.theme.store.ThemeRestoreTarget
import app.strata.launcher.core.theme.store.ThemeState
import app.strata.launcher.core.theme.store.ThemeSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [ThemeRepository] of ARCHITECTURE §4: wraps the pure [ThemeLibrary] (files, index, LKG, crash-loop guard)
 * with dispatchers and resolves the active document against the platform inputs and global settings.
 *
 * Threading (ARCHITECTURE §6): every library call runs on [themeDispatcher] (serial, I/O allowed); resolution
 * runs on [resolveDispatcher] with `mapLatest`. Nothing here touches the disk on the caller's thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultThemeRepository(
    private val createLibrary: () -> ThemeLibrary,
    private val files: () -> ThemeFileStore,
    private val bootAttempts: BootAttempts,
    /** Platform inputs without `systemDark` (both variants are resolved), plus null until the first read. */
    private val platformInputs: Flow<PlatformInputs?>,
    private val settings: Flow<Settings>,
    private val themeDispatcher: CoroutineDispatcher,
    private val resolveDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val logger: Logger,
    /** Platform font/image checks of imports (THEME_SYSTEM §10.3.3). */
    private val media: ThemeMedia = ThemeMedia.TRUSTING,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /**
     * Where [state] is written. The app passes the main dispatcher: compositions collect [state] directly
     * (`ThemeHost`), and a value set from a worker thread resumes them there. Under the Compose test clock that
     * resumption runs the recomposition inline on the worker ("multithreaded access to SnapshotStateObserver",
     * GMD API 34). Tests that need synchronous results keep the default.
     */
    private val publishDispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
) : ThemeRepository,
    ThemeManager,
    ThemeEditorStore,
    ThemeBackupStore {
    /** The document to render and what the boot said about it. */
    private data class Active(
        val document: ThemeDocument,
        val banner: ThemeBanner?,
        val generation: Int,
        val awaitingFirstFrame: Boolean,
        val safeMode: Boolean,
        /** A substitute is shown (LKG, Strata Default after a failure, guard, safe mode); survives banner dismissal. */
        val fallback: Boolean,
        val bannerDismissed: Boolean = false,
        /** Names in `themes/fonts/` and `themes/images/` without extension (resolver references, §4.3/§4.4). */
        val userFonts: Set<String>,
        val images: Set<String>,
    )

    private val started = AtomicBoolean(false)
    private val bootTimer = AtomicBoolean(false)
    private val active = MutableStateFlow<Active?>(null)
    private val stateFlow = MutableStateFlow(ThemeState())
    private var library: ThemeLibrary? = null
    private var booted = false

    /** Latest inputs and globals seen by the resolution pipeline (import previews resolve against them). */
    @Volatile private var latestInputs: PlatformInputs? = null

    @Volatile private var latestGlobals: ThemeGlobals = ThemeGlobals()
    private val importer by lazy { ThemeImporter(media = media, newId = newId) }

    /**
     * Prepared imports waiting for the user's confirmation, oldest first, at most [MAX_PENDING_IMPORTS] (each can hold
     * up to 24 MiB of media). Their media hashes are pinned against garbage collection until commit or discard.
     */
    private val pendingImports = LinkedHashMap<String, Pair<ImportCandidate, ImportPlan>>()

    private fun pendingPut(
        token: String,
        value: Pair<ImportCandidate, ImportPlan>,
    ) = synchronized(pendingImports) {
        pendingImports[token] = value
        while (pendingImports.size > MAX_PENDING_IMPORTS) pendingImports.remove(pendingImports.keys.first())
    }

    private fun pendingRemove(token: String) = synchronized(pendingImports) { pendingImports.remove(token) }

    /** Images stored by create-from-image in this process; kept until a draft or a saved theme references them. */
    private val editorImages: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    private fun pendingMedia(): Set<String> =
        editorImages.toSet() +
            synchronized(pendingImports) {
                pendingImports.values.flatMapTo(HashSet()) { (c, _) ->
                    ThemeImporter.userFonts(c.parsed) +
                        listOfNotNull(
                            c.parsed.document.wallpaper.image
                                ?.sha256,
                        )
                }
            }

    /** Pack notices already shown and dismissed: `generation|package|unusable` (once per apply, FR-THEME-25). */
    private val dismissedPackNotices = MutableStateFlow<Set<String>>(emptySet())

    private val revisionFlow = MutableStateFlow(0)
    override val revision: StateFlow<Int> = revisionFlow.asStateFlow()

    override val state: StateFlow<ThemeState> = stateFlow.asStateFlow()

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(themeDispatcher) { traced(TraceSections.THEME_BOOT) { boot() } }
        scope.launch(publishDispatcher) {
            val globals = settings.map(::globalsOf).catch { emit(ThemeGlobals()) }.distinctUntilChanged()
            val resolved =
                combine(active.filterNotNull(), platformInputs.filterNotNull(), globals) { a, p, g -> Triple(a, p, g) }
                    .mapLatest { (a, p, g) ->
                        latestInputs = p
                        latestGlobals = g
                        a to traced(TraceSections.THEME_RESOLVE) { resolvePair(a, p, g) }
                    }.flowOn(resolveDispatcher)
            combine(resolved, dismissedPackNotices) { r, d -> r to d }
                .collect { (ar, dismissed) ->
                    val (a, resolvedPair) = ar
                    val (pair, fellBack) = resolvedPair
                    stateFlow.value =
                        ThemeState(
                            themes = pair,
                            activeId = a.document.id,
                            loaded = true,
                            banner =
                                if (a.bannerDismissed) {
                                    null
                                } else {
                                    a.banner
                                        ?: if (fellBack) RESOLUTION_FAILED else null
                                },
                            applyGeneration = a.generation,
                            awaitingFirstFrame = a.awaitingFirstFrame,
                            safeMode = a.safeMode,
                            fallback = a.fallback || fellBack,
                            packNotice = packNoticeOf(pair, a.generation, dismissed),
                        )
                }
        }
    }

    private fun boot() {
        if (booted) return
        booted = true
        val safeMode = bootAttempts.onStart()
        val result =
            try {
                createLibrary()
                    .also {
                        it.pinnedMedia = ::pendingMedia
                        library = it
                    }.boot(safeMode)
            } catch (e: Exception) {
                // The library never throws for bad data; this is a programming error. Render the default.
                logger.e(TAG, "theme boot failed", e)
                BootResult(StrataDefault.document, BootSource.STRATA_DEFAULT, showBanner = true)
            }
        if (result.source != BootSource.ACTIVE && result.source != BootSource.STRATA_DEFAULT) {
            logger.w(TAG, "boot used ${result.source}")
        }
        val store = files()
        active.value =
            Active(
                document = result.document,
                banner = bannerOf(result),
                generation = 0,
                awaitingFirstFrame = true,
                safeMode = result.source == BootSource.SAFE_MODE,
                fallback = result.showBanner,
                userFonts = names(store, FONTS_DIR),
                images = names(store, IMAGES_DIR),
            )
    }

    override fun apply(id: String) {
        scope.launch(themeDispatcher) { applyNow(id) }
    }

    /** On [themeDispatcher]: loads [id], arms the crash-loop guard and renders it. */
    private fun applyNow(id: String): Boolean {
        val lib = library ?: return false
        when (val loaded = lib.loadTheme(id)) {
            is Outcome.Ok -> {
                lib.apply(id)
                showActive(loaded.value.document)
                return true
            }

            is Outcome.Err -> {
                logger.w(TAG, "apply refused: ${ThemeErrorText.english(loaded.error)}")
                return false
            }
        }
    }

    private fun showActive(document: ThemeDocument) {
        val store = files()
        active.update { a ->
            // An explicit choice ends safe mode and the fallback for the theme (§12, like causedProblem).
            a?.copy(
                document = document,
                banner = null,
                bannerDismissed = false,
                generation = a.generation + 1,
                awaitingFirstFrame = true,
                safeMode = false,
                fallback = false,
                userFonts = names(store, FONTS_DIR),
                images = names(store, IMAGES_DIR),
            )
        }
    }

    override fun onFirstFrame(generation: Int) {
        // ARCHITECTURE §10: the start is good 10 s after its first frame (from any activity, home or settings).
        if (bootTimer.compareAndSet(false, true)) {
            scope.launch {
                delay(BOOT_GOOD_AFTER_MS)
                onBootSucceeded()
            }
        }
        scope.launch(themeDispatcher) {
            val a = active.value ?: return@launch
            if (a.generation != generation || !a.awaitingFirstFrame) return@launch
            library?.onFirstFrame()
            // update {}: a concurrent dismissBanner() must not be overwritten by a stale copy.
            active.update { it?.takeIf { c -> c.generation == generation }?.copy(awaitingFirstFrame = false) ?: it }
        }
    }

    override fun onBootSucceeded() {
        scope.launch(themeDispatcher) {
            bootAttempts.onSucceeded()
            library?.onBootSucceeded()
        }
    }

    override fun onStableRender() {
        scope.launch(themeDispatcher) {
            val a = active.value ?: return@launch
            val shown = stateFlow.value
            // Safe mode, boot fallbacks and failed resolutions render a substitute; only an applied theme that was
            // resolved as itself becomes last-known-good. Banner dismissal does not change that.
            if (a.safeMode || a.fallback || a.awaitingFirstFrame) return@launch
            if (!shown.loaded || shown.applyGeneration != a.generation || shown.fallback) return@launch
            library?.onStableRender()
        }
    }

    override fun dismissPackNotice() {
        val notice = stateFlow.value.packNotice ?: return
        val generation = stateFlow.value.applyGeneration
        dismissedPackNotices.update { it + noticeKey(generation, notice) }
    }

    override fun dismissBanner() {
        active.update { it?.copy(bannerDismissed = true) }
        stateFlow.update { it.copy(banner = null) }
    }

    override suspend fun library(): List<ThemeSummary> =
        withContext(themeDispatcher) { library?.summaries() ?: listOf(DEFAULT_SUMMARY) }

    // ---- ThemeManager (THEME_SYSTEM §10, §11.3) ----------------------------------------------------

    /** Runs [block] on the theme dispatcher with the booted library (booting it first if needed). */
    private suspend fun <T> withLibrary(block: (ThemeLibrary) -> T): T =
        withContext(themeDispatcher) {
            if (!booted) {
                start()
                boot()
            }
            block(checkNotNull(library))
        }

    private fun changed() {
        revisionFlow.update { it + 1 }
    }

    // ---- ThemeBackupStore (ARCHITECTURE §12, THEME_SYSTEM §11.4) -----------------------------------

    override suspend fun backupData(): ThemeBackupData = withLibrary { it.backupData() }

    override suspend fun restoreThemes(target: ThemeRestoreTarget) {
        withLibrary { lib ->
            val next = lib.restoreThemes(target)
            changed()
            if (next != null) {
                lib.loadTheme(next).let { if (it is Outcome.Ok) showActive(it.value.document) }
            }
        }
    }

    override suspend fun revalidateStored(): Int =
        withLibrary { lib ->
            val activeBefore = lib.currentIndex.activeThemeId
            val moved = lib.revalidateStored()
            if (moved > 0) {
                changed()
                val active = lib.currentIndex.activeThemeId
                if (!active.equals(activeBefore, ignoreCase = true)) {
                    lib.loadTheme(active).let { if (it is Outcome.Ok) showActive(it.value.document) }
                }
            }
            moved
        }

    override suspend fun entries(): List<ThemeSummary> = withLibrary { it.summaries() }

    override suspend fun prepareImport(bytes: ByteArray): Outcome<ImportPreview, ImportError> {
        var token: String? = null
        try {
            return withLibrary { lib ->
                val candidate =
                    try {
                        importer.prepare(bytes)
                    } catch (e: Exception) {
                        // Pure code is total; a platform decoder bug must still not escape (NFR-THEME-01).
                        logger.e(TAG, "import failed", e)
                        Outcome.Err(ImportError.Bundle(BundleProblem.DAMAGED, "import failed"))
                    } catch (e: OutOfMemoryError) {
                        logger.e(TAG, "import ran out of memory", e)
                        Outcome.Err(ImportError.TOO_LARGE)
                    }
                when (candidate) {
                    is Outcome.Err -> {
                        candidate
                    }

                    is Outcome.Ok -> {
                        try {
                            val plan = importer.plan(candidate.value, lib.snapshot())
                            val t = newId().also { token = it }
                            pendingPut(t, candidate.value to plan)
                            Outcome.Ok(previewOf(t, candidate.value, plan))
                        } catch (e: Exception) {
                            token?.let(::pendingRemove)
                            logger.e(TAG, "import preview failed", e)
                            Outcome.Err(ImportError.Bundle(BundleProblem.DAMAGED, "import failed"))
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            // The caller is gone and will never commit or discard: do not keep the candidate (and its media).
            token?.let(::pendingRemove)
            throw e
        }
    }

    private fun previewOf(
        token: String,
        c: ImportCandidate,
        plan: ImportPlan,
    ): ImportPreview {
        val doc = c.parsed.document
        val store = files()
        val fonts = names(store, FONTS_DIR) + c.fonts.map { it.sha256 }
        val images = names(store, IMAGES_DIR) + listOfNotNull(c.image?.sha256)
        val base = (latestInputs ?: PlatformInputs(apiLevel = 0)).copy(userFonts = fonts, images = images)
        val onError: (Throwable) -> Unit = { logger.e(TAG, "preview resolution failed", it) }
        val g = latestGlobals
        val light = ThemeResolver.resolveSafely(doc, base.copy(systemDark = false), g, onError).theme
        val dark = ThemeResolver.resolveSafely(doc, base.copy(systemDark = true), g, onError).theme
        // The badge describes the theme's own colours, not what enforcement makes of them (§7.6).
        val raw = g.copy(enforceContrast = false)
        val statuses =
            listOf(false, true).map {
                ThemeResolver
                    .resolveSafely(doc, base.copy(systemDark = it), raw, onError)
                    .theme.contrast.status
            }
        val missingFonts = ThemeImporter.userFonts(c.parsed).count { it !in fonts }
        return ImportPreview(
            token = token,
            themeId = doc.id,
            name = doc.name,
            displayName = plan.displayName,
            replaceName = plan.replaceName,
            author = doc.author,
            description = doc.description,
            conflict = plan.conflict,
            contrast = statuses.maxBy { it.ordinal },
            newerSettingsKept = ThemeImporter.newerSettings(c.parsed),
            missingIconPack =
                light.icons.missingPackPackage?.let {
                    IconPackRef(it, light.icons.missingPackDisplayName, doc.icons.pack?.fallback ?: PackFallback.SYSTEM)
                },
            missingFonts = missingFonts,
            fromBundle = c.fromBundle,
            hasWallpaperImage = c.image != null,
            preview = ThemePair(light, dark),
        )
    }

    override suspend fun commitImport(
        token: String,
        choice: ConflictChoice,
        apply: Boolean,
    ): Outcome<ImportCommit, ImportError> =
        withLibrary { lib ->
            val (candidate, plan) =
                pendingRemove(token)
                    ?: return@withLibrary Outcome.Err(ImportError.UNREADABLE)
            if (plan.conflict == ImportConflict.IDENTICAL) {
                val id = candidate.parsed.document.id
                val applied = apply && applyNow(id)
                return@withLibrary Outcome.Ok(ImportCommit(id, replaced = false, applied = applied))
            }
            val tree = importer.treeFor(candidate, plan, choice)
            try {
                val (saved, previous) = lib.commitImport(candidate, tree)
                val id = saved.document.id
                changed()
                val applied = apply && applyNow(id)
                if (!applied && lib.currentIndex.activeThemeId.equals(id, ignoreCase = true)) {
                    // The active theme was replaced: render the new version.
                    applyNow(id)
                }
                Outcome.Ok(ImportCommit(id, replaced = previous != null, applied = applied))
            } catch (e: java.io.IOException) {
                logger.w(TAG, "import write failed", e)
                Outcome.Err(ImportError.NOT_ENOUGH_STORAGE)
            } catch (e: Exception) {
                // save() validates again; a library bug must not escape into the UI's scope (NFR-THEME-01).
                logger.e(TAG, "import commit failed", e)
                Outcome.Err(ImportError.Bundle(BundleProblem.DAMAGED, "import commit failed"))
            }
        }

    override fun discardImport(token: String) {
        pendingRemove(token)
    }

    override suspend fun undoReplace(id: String): Boolean =
        withLibrary { lib ->
            val restored =
                try {
                    lib.restore(id)
                } catch (e: Exception) {
                    logger.w(TAG, "undo of replace failed", e)
                    false
                }
            if (!restored) return@withLibrary false
            if (lib.currentIndex.activeThemeId.equals(id, ignoreCase = true)) applyNow(id)
            changed()
            true
        }

    override suspend fun duplicate(id: String): String? =
        withLibrary { lib ->
            try {
                lib.duplicate(id, newId())?.also { changed() }
            } catch (e: Exception) {
                logger.w(TAG, "duplicate failed", e)
                null
            }
        }

    override suspend fun editableCopy(id: String): String? =
        withLibrary { lib ->
            if (!lib.isBuiltIn(id) && !id.equals(StrataDefault.ID, ignoreCase = true)) return@withLibrary id
            try {
                lib.duplicate(id, newId())?.also { changed() }
            } catch (e: Exception) {
                logger.w(TAG, "copy for editing failed", e)
                null
            }
        }

    override suspend fun requestDelete(id: String): DeleteTicket? =
        withLibrary { lib ->
            if (lib.isBuiltIn(id) || id.equals(StrataDefault.ID, ignoreCase = true)) return@withLibrary null
            val name =
                lib.summaries().firstOrNull { it.id.equals(id, ignoreCase = true) }?.name ?: return@withLibrary null
            val sha =
                lib.currentIndex
                    .entry(id)
                    ?.fileSha256
                    .orEmpty()
            val target = lib.switchAwayFrom(id)
            if (target != null) {
                (lib.loadTheme(target) as? Outcome.Ok)?.let { showActive(it.value.document) }
            }
            DeleteTicket(id, name, wasActive = target != null, newActiveId = target, fileSha256 = sha)
        }

    override suspend fun undoDelete(ticket: DeleteTicket) {
        withLibrary { lib ->
            // Only undo our own switch: if the user chose another theme meanwhile, that choice stands.
            val stillOurSwitch = lib.currentIndex.activeThemeId.equals(ticket.newActiveId, ignoreCase = true)
            if (ticket.wasActive && stillOurSwitch) applyNow(ticket.id)
        }
    }

    override suspend fun commitDelete(ticket: DeleteTicket): Boolean =
        withLibrary { lib ->
            val entry = lib.currentIndex.entry(ticket.id) ?: return@withLibrary false
            // Re-imported, replaced or applied again inside the window: that newer intent wins.
            if (entry.fileSha256 != ticket.fileSha256) return@withLibrary false
            if (lib.currentIndex.activeThemeId.equals(ticket.id, ignoreCase = true)) return@withLibrary false
            try {
                lib.delete(ticket.id)
            } catch (e: Exception) {
                logger.w(TAG, "delete failed", e)
                return@withLibrary false
            }
            changed()
            true
        }

    override suspend fun setHidden(
        id: String,
        hidden: Boolean,
    ) {
        withLibrary { lib ->
            lib.setHidden(id, hidden)
            changed()
        }
    }

    override suspend fun exportJson(id: String): ExportFile? =
        withLibrary { lib ->
            val bytes = lib.exportJson(id) ?: return@withLibrary null
            ExportFile(fileName(lib, id, JSON_SUFFIX), MIME_JSON, bytes)
        }

    override suspend fun hasBundleMedia(id: String): Boolean = withLibrary { it.hasBundleMedia(id) }

    override suspend fun exportBundle(id: String): Outcome<ExportFile, ExportError> =
        withLibrary { lib ->
            when (val r = lib.exportBundle(id)) {
                is BundleExport.Ok -> Outcome.Ok(ExportFile(fileName(lib, id, ZIP_SUFFIX), MIME_ZIP, r.bytes))
                BundleExport.NotFound -> Outcome.Err(ExportError.NOT_FOUND)
                BundleExport.TooLarge -> Outcome.Err(ExportError.TOO_LARGE)
            }
        }

    override suspend fun wallpaperImage(id: String): ThemeImage? = withLibrary { it.wallpaperImage(id) }

    // ---- ThemeEditorStore (THEME_SYSTEM §13) ------------------------------------------------------

    override suspend fun openForEdit(id: String): EditTarget? =
        withLibrary { lib ->
            try {
                if (lib.isBuiltIn(id) || id.equals(StrataDefault.ID, ignoreCase = true)) {
                    val copyId = newId()
                    lib.copyTree(id, copyId)?.let { EditTarget(copyId, id, it, saved = null) }
                } else {
                    (lib.loadTheme(id) as? Outcome.Ok)?.value?.let {
                        EditTarget(it.document.id, it.document.id, it.tree, saved = it.tree)
                    }
                }
            } catch (e: Exception) {
                logger.w(TAG, "open for editing failed", e)
                null
            }
        }

    override suspend fun newTheme(name: String): EditTarget =
        withLibrary { lib ->
            val id = newId()
            val unique =
                try {
                    lib.uniqueName(name)
                } catch (e: Exception) {
                    name
                }
            EditTarget(
                id,
                null,
                StrataDefault.tree.with("id", JsonString(id)).with("name", JsonString(unique)),
                saved = null,
            )
        }

    override suspend fun themeTree(id: String): JsonObject? =
        withLibrary { lib ->
            try {
                (lib.loadTheme(id) as? Outcome.Ok)?.value?.tree
            } catch (e: Exception) {
                null
            }
        }

    override suspend fun saveEdited(tree: JsonObject): Outcome<JsonObject, EditorSaveError> =
        withLibrary { lib ->
            try {
                val saved = lib.save(tree, ThemeOrigin.USER)
                changed()
                if (lib.currentIndex.activeThemeId.equals(saved.document.id, ignoreCase = true)) {
                    showActive(saved.document)
                }
                Outcome.Ok(saved.tree)
            } catch (e: java.io.IOException) {
                logger.w(TAG, "theme save failed", e)
                Outcome.Err(EditorSaveError.NOT_ENOUGH_STORAGE)
            } catch (e: Exception) {
                logger.e(TAG, "theme save refused", e)
                Outcome.Err(EditorSaveError.INVALID)
            }
        }

    override suspend fun writeDraft(draft: EditorDraft): Boolean =
        withLibrary { lib ->
            try {
                lib.writeDraft(draft)
                true
            } catch (e: Exception) {
                logger.w(TAG, "draft write failed", e)
                false
            }
        }

    override suspend fun readDraft(sessionId: String): EditorDraft? =
        withLibrary { lib ->
            try {
                lib.readDraft(sessionId)
            } catch (e: Exception) {
                null
            }
        }

    override suspend fun deleteDraft(sessionId: String) {
        withLibrary { lib ->
            try {
                lib.deleteDraft(sessionId)
                changed()
            } catch (e: Exception) {
                logger.w(TAG, "draft delete failed", e)
            }
        }
    }

    override suspend fun drafts(): List<EditorDraft> =
        withLibrary { lib ->
            try {
                lib.drafts()
            } catch (e: Exception) {
                emptyList()
            }
        }

    override suspend fun imageSeed(
        bytes: ByteArray,
        keepAsWallpaper: Boolean,
    ): Outcome<ImageSeedResult, ImageSeedError> {
        val header = ImageCheck.header(bytes) ?: return Outcome.Err(ImageSeedError.UNSUPPORTED)
        // Decoding and re-encoding run off the library's serial dispatcher (they can take a while on a large photo);
        // only the write into the image store holds it.
        val decoded: Pair<Int?, ByteArray?> =
            try {
                withContext(resolveDispatcher) {
                    // The seed comes from a sampled decode, so a photo beyond the wallpaper pixel limits still works.
                    val seed = media.samplePixels(bytes, header, SEED_SAMPLE_SIDE)?.let(SeedExtractor::seed)
                    val wallpaperOk = bytes.size <= ImageCheck.MAX_BYTES && ImageCheck.withinLimits(header)
                    val stored =
                        if (seed == null || !keepAsWallpaper || !wallpaperOk) {
                            null
                        } else {
                            when (val out = media.prepareImage(bytes, header)) {
                                ImageOutcome.AsIs -> bytes
                                is ImageOutcome.Reencoded -> out.webp
                                ImageOutcome.Rejected -> null
                            }
                        }
                    seed to stored
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "image seed failed", e)
                return Outcome.Err(ImageSeedError.UNREADABLE)
            } catch (e: OutOfMemoryError) {
                return Outcome.Err(ImageSeedError.UNSUPPORTED)
            }
        val seed = decoded.first ?: return Outcome.Err(ImageSeedError.UNREADABLE)
        val stored = decoded.second
        var image: WallpaperImage? = null
        if (stored != null) {
            val sha = BundleCodec.sha256(stored)
            editorImages += sha
            val ok =
                withLibrary { lib ->
                    try {
                        lib.storeImage(ThemeImage(sha, ImageMediaType.WEBP, stored))
                        true
                    } catch (e: Exception) {
                        logger.w(TAG, "image store failed", e)
                        false
                    }
                }
            if (ok) image = WallpaperImage(sha, ImageMediaType.WEBP)
        }
        return Outcome.Ok(ImageSeedResult(seed, image, wallpaperSkipped = keepAsWallpaper && image == null))
    }

    override fun previewEnvironment(): PreviewEnvironment {
        val a = active.value
        val base = latestInputs ?: PlatformInputs(apiLevel = 0)
        return PreviewEnvironment(
            base.copy(
                userFonts = a?.userFonts ?: base.userFonts,
                images = (a?.images ?: base.images) + editorImages.toSet(),
            ),
            latestGlobals,
        )
    }

    private fun fileName(
        lib: ThemeLibrary,
        id: String,
        suffix: String,
    ): String {
        val name = lib.summaries().firstOrNull { it.id.equals(id, ignoreCase = true) }?.name ?: "theme"
        return safeFileName(name) + suffix
    }

    private fun resolvePair(
        a: Active,
        p: PlatformInputs,
        g: ThemeGlobals,
    ): Pair<ThemePair, Boolean> {
        val inputs = p.copy(userFonts = a.userFonts, images = a.images)
        val onError: (Throwable) -> Unit = { logger.e(TAG, "theme resolution failed", it) }
        val light = ThemeResolver.resolveSafely(a.document, inputs.copy(systemDark = false), g, onError)
        val dark = ThemeResolver.resolveSafely(a.document, inputs.copy(systemDark = true), g, onError)
        return ThemePair(light.theme, dark.theme) to (light.fellBack || dark.fellBack)
    }

    private fun names(
        store: ThemeFileStore,
        dir: String,
    ): Set<String> =
        try {
            store.list(dir).map { it.substringBefore('.') }.toSet()
        } catch (e: Exception) {
            emptySet()
        }

    companion object {
        private const val TAG = "Theme"
        const val FONTS_DIR = "fonts"
        const val BOOT_GOOD_AFTER_MS = 10_000L
        const val IMAGES_DIR = "images"
        const val JSON_SUFFIX = ".strata-theme.json"

        /** Prepared imports kept at once; older ones are dropped (their summary screens then fail on commit). */
        const val MAX_PENDING_IMPORTS = 4
        const val ZIP_SUFFIX = ".strata-theme.zip"
        const val MIME_JSON = "application/json"
        const val MIME_ZIP = "application/zip"

        /** Longer side of the decoded image the seed is taken from (Proposed; colour, not detail, matters). */
        const val SEED_SAMPLE_SIDE = 128

        /** A suggested export name (§10.1): characters file systems reject become `_`; never empty. */
        fun safeFileName(name: String): String =
            name
                .map { if (it.isISOControl() || it in "/\\:*?\"<>|") '_' else it }
                .joinToString("")
                .trim()
                .trimStart('.')
                .take(MAX_FILE_STEM)
                .ifEmpty { "theme" }

        private const val MAX_FILE_STEM = 80

        private val DEFAULT_SUMMARY =
            ThemeSummary(StrataDefault.ID, StrataDefault.NAME, builtIn = true, applicable = true)

        private val RESOLUTION_FAILED = ThemeBanner(ThemeBannerKind.LOAD_FAILED, "The theme could not be resolved.")

        private fun noticeKey(
            generation: Int,
            n: PackNotice,
        ): String = "$generation|${n.packageName}|${n.unusable}"

        /** FR-THEME-25: the notice of [pair]'s missing pack, unless dismissed for this apply. */
        fun packNoticeOf(
            pair: ThemePair,
            generation: Int,
            dismissed: Set<String>,
        ): PackNotice? {
            val icons = pair.light.icons
            val pkg = icons.missingPackPackage ?: return null
            val notice = PackNotice(pkg, icons.missingPackDisplayName, icons.missingPackUnusable)
            return notice.takeUnless { noticeKey(generation, it) in dismissed }
        }

        fun globalsOf(s: Settings): ThemeGlobals =
            ThemeGlobals(
                appearanceOverride =
                    when (s.appearanceOverride) {
                        AppearanceSetting.FOLLOW_THEME -> AppearanceOverride.FOLLOW_THEME
                        AppearanceSetting.SYSTEM -> AppearanceOverride.SYSTEM
                        AppearanceSetting.LIGHT -> AppearanceOverride.LIGHT
                        AppearanceSetting.DARK -> AppearanceOverride.DARK
                    },
                enforceContrast = s.enforceContrast,
                hapticsEnabled = s.hapticsEnabled,
            )

        fun bannerOf(r: BootResult): ThemeBanner? {
            if (!r.showBanner) return null
            val kind =
                when (r.source) {
                    BootSource.SAFE_MODE -> ThemeBannerKind.SAFE_MODE
                    BootSource.CRASH_GUARD -> ThemeBannerKind.CRASH_GUARD
                    else -> ThemeBannerKind.LOAD_FAILED
                }
            return ThemeBanner(kind, r.problem?.let(::detailsOf) ?: defaultDetails(kind))
        }

        private fun detailsOf(e: ThemeError): String =
            if (e is ThemeError.NotATheme) e.issue.message else ThemeErrorText.english(e)

        private fun defaultDetails(kind: ThemeBannerKind): String =
            when (kind) {
                ThemeBannerKind.SAFE_MODE -> "Strata stopped twice while starting, so it started in safe mode."
                ThemeBannerKind.CRASH_GUARD -> "The theme caused a problem and was not applied."
                ThemeBannerKind.LOAD_FAILED -> "The theme could not be loaded."
            }
    }
}

/** A synchronous `android.os.Trace` section (T-THEME-44: theme boot and resolve on the cold-start path). */
private inline fun <T> traced(
    name: String,
    block: () -> T,
): T {
    android.os.Trace.beginSection(name)
    try {
        return block()
    } finally {
        android.os.Trace.endSection()
    }
}
