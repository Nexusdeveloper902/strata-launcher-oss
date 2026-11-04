package app.strata.launcher.core.data.theme

import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.AppearanceSetting
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.model.ThemeDocument
import app.strata.launcher.core.theme.parse.ThemeError
import app.strata.launcher.core.theme.parse.ThemeErrorText
import app.strata.launcher.core.theme.resolve.AppearanceOverride
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.ThemeGlobals
import app.strata.launcher.core.theme.resolve.ThemeResolver
import app.strata.launcher.core.theme.store.BootResult
import app.strata.launcher.core.theme.store.BootSource
import app.strata.launcher.core.theme.store.ThemeBanner
import app.strata.launcher.core.theme.store.ThemeBannerKind
import app.strata.launcher.core.theme.store.ThemeFileStore
import app.strata.launcher.core.theme.store.ThemeLibrary
import app.strata.launcher.core.theme.store.ThemePair
import app.strata.launcher.core.theme.store.ThemeRepository
import app.strata.launcher.core.theme.store.ThemeState
import app.strata.launcher.core.theme.store.ThemeSummary
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
) : ThemeRepository {
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

    override val state: StateFlow<ThemeState> = stateFlow.asStateFlow()

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(themeDispatcher) { boot() }
        scope.launch {
            val globals = settings.map(::globalsOf).catch { emit(ThemeGlobals()) }.distinctUntilChanged()
            combine(active.filterNotNull(), platformInputs.filterNotNull(), globals) { a, p, g -> Triple(a, p, g) }
                .mapLatest { (a, p, g) -> a to resolvePair(a, p, g) }
                .flowOn(resolveDispatcher)
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
        val safeMode = bootAttempts.onStart()
        val result =
            try {
                createLibrary().also { library = it }.boot(safeMode)
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
        scope.launch(themeDispatcher) {
            val lib = library ?: return@launch
            when (val loaded = lib.loadTheme(id)) {
                is app.strata.launcher.core.common.Outcome.Ok -> {
                    lib.apply(id)
                    active.update { a ->
                        // An explicit choice ends safe mode and the fallback for the theme (§12, like causedProblem).
                        a?.copy(
                            document = loaded.value.document,
                            banner = null,
                            bannerDismissed = false,
                            generation = a.generation + 1,
                            awaitingFirstFrame = true,
                            safeMode = false,
                            fallback = false,
                        )
                    }
                }

                is app.strata.launcher.core.common.Outcome.Err -> {
                    logger.w(TAG, "apply refused: ${ThemeErrorText.english(loaded.error)}")
                }
            }
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
