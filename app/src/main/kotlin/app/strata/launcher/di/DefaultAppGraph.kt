package app.strata.launcher.di

import android.content.ComponentName
import android.content.Context
import android.view.View
import app.strata.launcher.BuildConfig
import app.strata.launcher.badges.BadgeListenerService
import app.strata.launcher.core.common.AppDispatchers
import app.strata.launcher.core.common.Clock
import app.strata.launcher.core.common.log.LogLevel
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.common.log.RingLogger
import app.strata.launcher.core.common.log.RotatingFileLogSink
import app.strata.launcher.core.data.Databases
import app.strata.launcher.core.data.LazyResource
import app.strata.launcher.core.data.ResetNoticeBoard
import app.strata.launcher.core.data.catalog.AppCatalogRepository
import app.strata.launcher.core.data.launchlog.RoomLaunchLogRepository
import app.strata.launcher.core.data.list.AssembledAppListSource
import app.strata.launcher.core.data.overrides.RoomFavoritesRepository
import app.strata.launcher.core.data.overrides.RoomOverridesRepository
import app.strata.launcher.core.data.settings.DataStoreSettingsRepository
import app.strata.launcher.core.data.theme.AndroidThemeMedia
import app.strata.launcher.core.data.theme.AssetBuiltInThemeSource
import app.strata.launcher.core.data.theme.AtomicFileThemeFileStore
import app.strata.launcher.core.data.theme.BootAttempts
import app.strata.launcher.core.data.theme.DataStoreThemeIndexStore
import app.strata.launcher.core.data.theme.DefaultThemeRepository
import app.strata.launcher.core.data.widgets.RoomWidgetRepository
import app.strata.launcher.core.platformapi.apps.AppCatalog
import app.strata.launcher.core.platformapi.apps.AppLauncher
import app.strata.launcher.core.platformapi.apps.AppListSource
import app.strata.launcher.core.platformapi.apps.FavoritesRepository
import app.strata.launcher.core.platformapi.apps.LaunchLogRepository
import app.strata.launcher.core.platformapi.apps.OverridesRepository
import app.strata.launcher.core.platformapi.apps.ProfileSource
import app.strata.launcher.core.platformapi.apps.ShortcutSource
import app.strata.launcher.core.platformapi.icons.IconProvider
import app.strata.launcher.core.platformapi.notifications.BadgeSource
import app.strata.launcher.core.platformapi.settings.ResetNotices
import app.strata.launcher.core.platformapi.settings.SettingsRepository
import app.strata.launcher.core.platformapi.system.ContactsSource
import app.strata.launcher.core.platformapi.system.DocumentIO
import app.strata.launcher.core.platformapi.system.Haptics
import app.strata.launcher.core.platformapi.system.LockService
import app.strata.launcher.core.platformapi.system.RoleController
import app.strata.launcher.core.platformapi.system.SharedFiles
import app.strata.launcher.core.platformapi.system.SystemIntents
import app.strata.launcher.core.platformapi.system.TextFolding
import app.strata.launcher.core.platformapi.system.TimeTicks
import app.strata.launcher.core.platformapi.widgets.WidgetHostController
import app.strata.launcher.core.platformapi.widgets.WidgetRepository
import app.strata.launcher.core.theme.store.ThemeEditorStore
import app.strata.launcher.core.theme.store.ThemeLibrary
import app.strata.launcher.core.theme.store.ThemeManager
import app.strata.launcher.core.theme.store.ThemeRepository
import app.strata.launcher.diagnostics.LogcatSink
import app.strata.launcher.platform.apps.AndroidProfileSource
import app.strata.launcher.platform.apps.AndroidShortcutSource
import app.strata.launcher.platform.apps.LauncherAppsAppLauncher
import app.strata.launcher.platform.apps.LauncherAppsCatalogSource
import app.strata.launcher.platform.icons.AndroidIconProvider
import app.strata.launcher.platform.notifications.AndroidBadgeSource
import app.strata.launcher.platform.system.AndroidAnimatorScaleSource
import app.strata.launcher.platform.system.AndroidContactsSource
import app.strata.launcher.platform.system.AndroidDocumentIO
import app.strata.launcher.platform.system.AndroidDynamicColorsSource
import app.strata.launcher.platform.system.AndroidLockService
import app.strata.launcher.platform.system.AndroidRoleController
import app.strata.launcher.platform.system.AndroidSystemIntents
import app.strata.launcher.platform.system.AndroidTextFolding
import app.strata.launcher.platform.system.AndroidTimeTicks
import app.strata.launcher.platform.system.AndroidWallpaperColorsSource
import app.strata.launcher.platform.system.IcuCollation
import app.strata.launcher.platform.system.ViewHaptics
import app.strata.launcher.platform.widgets.AndroidWidgetHost
import app.strata.launcher.theme.FileProviderSharedFiles
import app.strata.launcher.theme.PlatformThemeInputs
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Production bindings. Everything is lazy: building the graph does no I/O (ARCHITECTURE §10 step 1). */
class DefaultAppGraph(context: Context) : AppGraph {
    private val appContext = context.applicationContext

    override val dispatchers: AppDispatchers = AppDispatchers.standard()

    /** Serialises catalog mutations (ARCHITECTURE §6). */
    private val catalogDispatcher by lazy { dispatchers.io.limitedParallelism(1) }

    override val logger: Logger by lazy {
        RingLogger(
            sinks =
                listOf(
                    RotatingFileLogSink(directory = { File(appContext.noBackupFilesDir, "logs") }),
                    LogcatSink(minLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.WARN),
                ),
        )
    }

    override val appScope: CoroutineScope by lazy {
        val handler = CoroutineExceptionHandler { _, t -> logger.e(TAG, "Uncaught coroutine failure", t) }
        CoroutineScope(SupervisorJob() + dispatchers.default + handler)
    }

    override val roleController: RoleController by lazy { AndroidRoleController(appContext) }

    override val resetNotices: ResetNotices by lazy { ResetNoticeBoard() }

    override val settingsRepository: SettingsRepository by lazy {
        DataStoreSettingsRepository.create(
            appContext,
            CoroutineScope(SupervisorJob() + dispatchers.io),
            resetNotices,
        )
    }

    override val locales: MutableStateFlow<List<String>> = MutableStateFlow(localeTags(appContext))

    private val collation by lazy { IcuCollation() }

    private val strataDb by lazy {
        LazyResource(dispatchers.io) { Databases.openStrata(appContext, logger, resetNotices) }
    }

    private val catalogRepository by lazy {
        AppCatalogRepository(
            database = strataDb,
            source = LauncherAppsCatalogSource(appContext, dispatchers.io, logger),
            clock = Clock.System,
            scope = appScope,
            catalogDispatcher = catalogDispatcher,
            logger = logger,
            sectionKeyOf = { label ->
                val model = collation.sections(locales.value)
                model.labels.getOrElse(model.bucketOf(label)) { "" }
            },
        )
    }

    override val appCatalog: AppCatalog get() = catalogRepository

    override val appList: AppListSource by lazy {
        AssembledAppListSource(
            catalog = catalogRepository,
            overrides = overridesRepository.overrides,
            locales = locales,
            collation = collation,
            defaultDispatcher = dispatchers.default,
            scope = appScope,
            snapshotLoaded = catalogRepository.snapshotLoaded,
            // Pause and lock show at once, before the catalog reload (T-LIFE-08/09).
            profiles = profileSource.profiles,
            badges = badgeSource.badges,
        )
    }

    override val appLauncher: AppLauncher by lazy { LauncherAppsAppLauncher(appContext, logger) }

    private val roomLaunchLog by lazy {
        RoomLaunchLogRepository(
            database = LazyResource(dispatchers.io) { Databases.openLaunchLog(appContext, logger) },
            settings = settingsRepository,
            clock = Clock.System,
        )
    }

    override val launchLog: LaunchLogRepository get() = roomLaunchLog

    override val textFolding: TextFolding by lazy { AndroidTextFolding() }

    private val overridesRepository by lazy { RoomOverridesRepository(strataDb) }

    override val overrides: OverridesRepository get() = overridesRepository

    override val favorites: FavoritesRepository by lazy { RoomFavoritesRepository(strataDb) }

    /** Icon render work: `Default.limitedParallelism(4)` (ARCHITECTURE §6). */
    override val iconProvider: IconProvider by lazy {
        AndroidIconProvider(
            context = appContext,
            renderDispatcher = dispatchers.default.limitedParallelism(ICON_PARALLELISM),
            ioDispatcher = dispatchers.io,
            scope = appScope,
            logger = logger,
        )
    }

    override val timeTicks: TimeTicks by lazy { AndroidTimeTicks(appContext) }

    override val lockService: LockService by lazy { AndroidLockService(appContext) }

    override val systemIntents: SystemIntents by lazy { AndroidSystemIntents(appContext) }

    private val viewHaptics = ViewHaptics()

    override val haptics: Haptics get() = viewHaptics

    /** Serialises every theme library call (files, index): the library is single-threaded (THEME_SYSTEM §11). */
    private val themeDispatcher by lazy { dispatchers.io.limitedParallelism(1) }

    private val themeFiles by lazy { AtomicFileThemeFileStore(root = { File(appContext.filesDir, THEMES_DIR) }) }

    private val themeInputs by lazy {
        PlatformThemeInputs(
            wallpaper = AndroidWallpaperColorsSource(appContext),
            dynamic = AndroidDynamicColorsSource(appContext),
            animator = AndroidAnimatorScaleSource(appContext),
            iconPacks = iconProvider.iconPacks,
            io = dispatchers.io,
            scope = appScope,
        )
    }

    override val themeRepository: ThemeRepository get() = defaultThemeRepository

    override val themeManager: ThemeManager get() = defaultThemeRepository

    override val themeEditor: ThemeEditorStore get() = defaultThemeRepository

    override val documents: DocumentIO by lazy { AndroidDocumentIO(appContext, dispatchers.io) }

    override val sharedFiles: SharedFiles by lazy {
        FileProviderSharedFiles(appContext, dispatchers.io, Clock.System, logger)
    }

    private val defaultThemeRepository: DefaultThemeRepository by lazy {
        DefaultThemeRepository(
            createLibrary = {
                ThemeLibrary(
                    files = themeFiles,
                    indexStore =
                        DataStoreThemeIndexStore.create(
                            // DataStore I/O must not run on the theme dispatcher, which blocks on it.
                            appContext,
                            scope = CoroutineScope(SupervisorJob() + dispatchers.io),
                        ),
                    builtIns = AssetBuiltInThemeSource(appContext.assets),
                    clock = Clock.System,
                )
            },
            files = { themeFiles },
            bootAttempts = BootAttempts({ File(appContext.noBackupFilesDir, BootAttempts.FILE_NAME) }, Clock.System),
            platformInputs = themeInputs.inputs,
            settings = settingsRepository.settings,
            themeDispatcher = themeDispatcher,
            resolveDispatcher = dispatchers.default,
            scope = appScope,
            publishDispatcher = dispatchers.main,
            logger = logger,
            media =
                AndroidThemeMedia(
                    importCacheDir = { File(appContext.cacheDir, AndroidThemeMedia.IMPORT_DIR) },
                    maxDecodeSide = {
                        val m = appContext.resources.displayMetrics
                        2 * maxOf(m.widthPixels, m.heightPixels)
                    },
                    logger = logger,
                ),
        )
    }

    private val themeStarted =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    override fun startTheme() {
        iconProvider.start()
        themeInputs.start()
        themeRepository.start()
        // THEME_SYSTEM §10.1: share copies older than 24 h are deleted at process start.
        if (themeStarted.compareAndSet(false, true)) {
            appScope.launch {
                sharedFiles.purgeExpired()
                withContext(dispatchers.io) { purgeImportCache() }
            }
        }
    }

    /**
     * Font-check temp dirs (`cacheDir/import/<random>/`) are deleted in `finally`; a process killed mid-check leaves one.
     * Dirs older than an hour are removed at start (a younger one may belong to an import running right now).
     */
    private fun purgeImportCache() {
        val now = System.currentTimeMillis()
        File(appContext.cacheDir, AndroidThemeMedia.IMPORT_DIR).listFiles()?.forEach { dir ->
            if (now - dir.lastModified() > IMPORT_CACHE_MAX_AGE_MS) dir.deleteRecursively()
        }
    }

    override fun refreshThemeInputs() {
        themeInputs.refresh()
        // A pack may have been installed or removed while the process was in the background.
        iconProvider.refreshPacks()
    }

    private val profileSource by lazy { AndroidProfileSource(appContext, dispatchers.io).also { it.start() } }

    override val profiles: ProfileSource get() = profileSource

    override val shortcuts: ShortcutSource by lazy { AndroidShortcutSource(appContext, dispatchers.io, logger) }

    /**
     * FR-NOTIF-01: created with the list (cheap, no I/O); follows settings field 13. The listener service reports to it
     * through [AndroidBadgeSource.instance].
     */
    private val badgeSource: AndroidBadgeSource by lazy {
        AndroidBadgeSource(
            context = appContext,
            listener = ComponentName(appContext, BadgeListenerService::class.java),
            worker = dispatchers.io.limitedParallelism(1),
            publish = dispatchers.main,
            scope = appScope,
            logger = logger,
        ).also { source ->
            appScope.launch {
                settingsRepository.settings
                    .map { it.badgesEnabled }
                    .catch { emit(false) }
                    .distinctUntilChanged()
                    .collect { source.setActive(it) }
            }
        }
    }

    override val badges: BadgeSource get() = badgeSource

    override val contacts: ContactsSource by lazy { AndroidContactsSource(appContext, dispatchers.io, logger) }

    /** Lazy (ARCHITECTURE §10): the host is created when home first starts listening. */
    override val widgetHost: WidgetHostController by lazy { AndroidWidgetHost(appContext, dispatchers.io, logger) }

    override val widgets: WidgetRepository by lazy { RoomWidgetRepository(strataDb) }

    override fun startCatalog() {
        profileSource
        catalogRepository.start()
        appScope.launch(dispatchers.io) { runCatching { roomLaunchLog.pruneExpired() } }
    }

    override fun attachWindow(view: View?) {
        if (view == null) viewHaptics.detachAll() else viewHaptics.attach(view)
    }

    private companion object {
        const val TAG = "AppGraph"
        const val ICON_PARALLELISM = 4
        const val THEMES_DIR = "themes"
        const val IMPORT_CACHE_MAX_AGE_MS = 60 * 60 * 1000L
    }
}

/** Locale tags of [context]'s configuration, primary first. */
fun localeTags(context: Context): List<String> {
    val list = context.resources.configuration.locales
    return (0 until list.size()).map { list[it].toLanguageTag() }.ifEmpty { listOf("en") }
}
