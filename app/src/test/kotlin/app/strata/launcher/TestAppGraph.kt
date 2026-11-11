package app.strata.launcher

import androidx.test.core.app.ApplicationProvider
import app.strata.launcher.core.common.AppDispatchers
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.theme.store.ThemeEditorStore
import app.strata.launcher.core.theme.store.ThemeManager
import app.strata.launcher.di.AppGraph
import app.strata.launcher.testing.fakes.FakeAppCatalog
import app.strata.launcher.testing.fakes.FakeAppLauncher
import app.strata.launcher.testing.fakes.FakeAppListSource
import app.strata.launcher.testing.fakes.FakeBadgeSource
import app.strata.launcher.testing.fakes.FakeContactsSource
import app.strata.launcher.testing.fakes.FakeDocumentIO
import app.strata.launcher.testing.fakes.FakeFavoritesRepository
import app.strata.launcher.testing.fakes.FakeHaptics
import app.strata.launcher.testing.fakes.FakeIconProvider
import app.strata.launcher.testing.fakes.FakeLaunchLogRepository
import app.strata.launcher.testing.fakes.FakeLockService
import app.strata.launcher.testing.fakes.FakeOverridesRepository
import app.strata.launcher.testing.fakes.FakeProfileSource
import app.strata.launcher.testing.fakes.FakeResetNotices
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fakes.FakeSharedFiles
import app.strata.launcher.testing.fakes.FakeShortcutSource
import app.strata.launcher.testing.fakes.FakeSystemIntents
import app.strata.launcher.testing.fakes.FakeTextFolding
import app.strata.launcher.testing.fakes.FakeThemeEditorStore
import app.strata.launcher.testing.fakes.FakeThemeManager
import app.strata.launcher.testing.fakes.FakeThemeRepository
import app.strata.launcher.testing.fakes.FakeTimeTicks
import app.strata.launcher.testing.fakes.RecordingLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** [AppGraph] of fakes (ARCHITECTURE §7: tests swap the graph wholesale). */
class TestAppGraph(
    override val roleController: FakeRoleController = FakeRoleController(),
    override val settingsRepository: FakeSettingsRepository = FakeSettingsRepository(),
    override val logger: RecordingLogger = RecordingLogger(),
    override val appList: FakeAppListSource = FakeAppListSource(),
    override val appCatalog: FakeAppCatalog = FakeAppCatalog(),
    override val appLauncher: FakeAppLauncher = FakeAppLauncher(),
    override val launchLog: FakeLaunchLogRepository = FakeLaunchLogRepository(),
    override val haptics: FakeHaptics = FakeHaptics(),
    override val resetNotices: FakeResetNotices = FakeResetNotices(),
    override val textFolding: FakeTextFolding = FakeTextFolding(),
    override val systemIntents: FakeSystemIntents = FakeSystemIntents(),
    override val overrides: FakeOverridesRepository = FakeOverridesRepository(),
    override val favorites: FakeFavoritesRepository = FakeFavoritesRepository(),
    override val iconProvider: FakeIconProvider = FakeIconProvider(),
    override val timeTicks: FakeTimeTicks = FakeTimeTicks(),
    override val lockService: FakeLockService = FakeLockService(),
    override val themeRepository: FakeThemeRepository = FakeThemeRepository(),
    override val themeManager: ThemeManager = FakeThemeManager(),
    override val themeEditor: ThemeEditorStore = FakeThemeEditorStore(),
    override val documents: FakeDocumentIO = FakeDocumentIO(),
    override val sharedFiles: FakeSharedFiles = FakeSharedFiles(),
    override val profiles: FakeProfileSource = FakeProfileSource(),
    override val shortcuts: FakeShortcutSource = FakeShortcutSource(),
    override val badges: FakeBadgeSource = FakeBadgeSource(),
    override val contacts: FakeContactsSource = FakeContactsSource(),
) : AppGraph {
    constructor(roleController: FakeRoleController = FakeRoleController(), onboarded: Boolean) :
        this(roleController, FakeSettingsRepository(Settings(onboardingDone = onboarded)))

    override val dispatchers =
        AppDispatchers(
            main = Dispatchers.Main,
            io = Dispatchers.Unconfined,
            default = Dispatchers.Unconfined,
        )
    override val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    override val locales = MutableStateFlow(listOf("en"))
    var catalogStarts = 0
        private set

    override fun startCatalog() {
        catalogStarts++
    }

    /** Installs this graph into the Robolectric application before any activity starts. */
    fun install(): TestAppGraph =
        apply {
            ApplicationProvider.getApplicationContext<StrataApplication>().graph = this
        }
}
