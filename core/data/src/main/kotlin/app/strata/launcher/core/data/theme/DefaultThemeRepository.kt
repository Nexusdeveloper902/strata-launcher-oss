package app.strata.launcher.core.data.theme

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.AppearanceSetting
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.importing.BundleProblem
import app.strata.launcher.core.theme.importing.ConflictChoice
import app.strata.launcher.core.theme.importing.ImportCandidate
import app.strata.launcher.core.theme.importing.ImportConflict
import app.strata.launcher.core.theme.importing.ImportError
import app.strata.launcher.core.theme.importing.ImportPlan
import app.strata.launcher.core.theme.importing.ThemeImage
import app.strata.launcher.core.theme.importing.ThemeImporter
import app.strata.launcher.core.theme.importing.ThemeMedia
import app.strata.launcher.core.theme.model.ThemeDocument
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
import app.strata.launcher.core.theme.store.ExportError
import app.strata.launcher.core.theme.store.ExportFile
import app.strata.launcher.core.theme.store.ImportCommit
import app.strata.launcher.core.theme.store.ImportPreview
import app.strata.launcher.core.theme.store.ThemeBanner
import app.strata.launcher.core.theme.store.ThemeBannerKind
import app.strata.launcher.core.theme.store.ThemeFileStore
import app.strata.launcher.core.theme.store.ThemeLibrary
import app.strata.launcher.core.theme.store.ThemeManager
import app.strata.launcher.core.theme.store.ThemePair
import app.strata.launcher.core.theme.store.ThemeRepository
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
) : ThemeRepository,
    ThemeManager {
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

    private fun pendingMedia(): Set<String> =
        synchronized(pendingImports) {
            pendingImports.values.flatMapTo(HashSet()) { (c, _) ->
                ThemeImporter.userFonts(c.parsed) +
                    listOfNotNull(
                        c.parsed.document.wallpaper.image
                            ?.sha256,
                    )
            }
        }

    private val revisionFlow = MutableStateFlow(0)
    override val revision: StateFlow<Int> = revisionFlow.asStateFlow()

    override val state: StateFlow<ThemeState> = stateFlow.asStateFlow()

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(themeDispatcher) { boot() }
        scope.launch {
            val globals = settings.map(::globalsOf).catch { emit(ThemeGlobals()) }.distinctUntilChanged()
            combine(active.filterNotNull(), platformInputs.filterNotNull(), globals) { a, p, g -> Triple(a, p, g) }
                .mapLatest { (a, p, g) ->
                    latestInputs = p
                    latestGlobals = g
                    a to resolvePair(a, p, g)
                }.flowOn(resolveDispatcher)
                .collect { (a, resolved) ->
                    val (pair, fellBack) = resolved
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
            // Installed icon packs are discovered with the icon work (M5 part 2): until then no pack notice.
            missingIconPack = null,
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
