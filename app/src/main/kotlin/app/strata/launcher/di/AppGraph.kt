package app.strata.launcher.di

import android.view.View
import app.strata.launcher.core.common.AppDispatchers
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.platformapi.apps.AppCatalog
import app.strata.launcher.core.platformapi.apps.AppLauncher
import app.strata.launcher.core.platformapi.apps.AppListSource
import app.strata.launcher.core.platformapi.apps.FavoritesRepository
import app.strata.launcher.core.platformapi.apps.LaunchLogRepository
import app.strata.launcher.core.platformapi.apps.OverridesRepository
import app.strata.launcher.core.platformapi.apps.ProfileSource
import app.strata.launcher.core.platformapi.apps.ShortcutSource
import app.strata.launcher.core.platformapi.backup.BackupService
import app.strata.launcher.core.platformapi.icons.IconProvider
import app.strata.launcher.core.platformapi.notifications.BadgeSource
import app.strata.launcher.core.platformapi.settings.ResetNotices
import app.strata.launcher.core.platformapi.settings.SettingsRepository
import app.strata.launcher.core.platformapi.system.ContactsSource
import app.strata.launcher.core.platformapi.system.DiagnosticLogService
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
import app.strata.launcher.core.theme.store.ThemeManager
import app.strata.launcher.core.theme.store.ThemeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Manual, compile-time dependency graph (ARCHITECTURE §7). One instance per process, created in
 * [app.strata.launcher.StrataApplication.onCreate]. Tests replace it wholesale with fakes.
 */
interface AppGraph {
    val dispatchers: AppDispatchers
    val logger: Logger

    /** Application scope: SupervisorJob + Default + a handler that logs and never rethrows (§6). */
    val appScope: CoroutineScope
    val roleController: RoleController
    val settingsRepository: SettingsRepository
    val resetNotices: ResetNotices
    val appCatalog: AppCatalog
    val appList: AppListSource
    val appLauncher: AppLauncher
    val launchLog: LaunchLogRepository
    val haptics: Haptics
    val textFolding: TextFolding
    val systemIntents: SystemIntents
    val overrides: OverridesRepository
    val favorites: FavoritesRepository
    val iconProvider: IconProvider
    val timeTicks: TimeTicks
    val lockService: LockService

    /** Work profile and private space (FR-APPS-09/10). */
    val profiles: ProfileSource

    /** App shortcuts in the sheet (FR-APPS-08). */
    val shortcuts: ShortcutSource

    /** Notification badges (FR-NOTIF-01…03): opt-in listener, counts in memory only. */
    val badges: BadgeSource

    /** Contact search (FR-SEARCH-06): opt-in, `READ_CONTACTS` requested only when turned on. */
    val contacts: ContactsSource

    /** The platform widget host (FR-WIDGET-01…04): bind, consent, configure, host views. */
    val widgetHost: WidgetHostController

    /** The header widget's placement (Room `widget_placement`). */
    val widgets: WidgetRepository

    /** Active theme and library (ARCHITECTURE §4); [ThemeRepository.start] boots it off the main thread. */
    val themeRepository: ThemeRepository

    /** Library management, import and export (THEME_SYSTEM §10, §11.3); same instance as [themeRepository]. */
    val themeManager: ThemeManager

    /** The theme editor's storage (THEME_SYSTEM §13: open, save, drafts, image seed); same instance as [themeRepository]. */
    val themeEditor: ThemeEditorStore

    /** SAF document reads and writes (`content:` only). */
    val documents: DocumentIO

    /** Temporary `cacheDir/share/` copies behind the FileProvider. */
    val sharedFiles: SharedFiles

    /** About -> "Save diagnostic log" (ARCHITECTURE §11, T-PRIV-06). */
    val diagnostics: DiagnosticLogService

    /** Manual backup and restore (FR-BACKUP-01…04); bound to `:backup`. */
    val backup: BackupService

    /** BCP-47 tags of the current configuration's locales (sections, collation). */
    val locales: MutableStateFlow<List<String>>

    /** Reads the snapshot and subscribes to package changes (idempotent; ARCHITECTURE §10 step 2b). */
    fun startCatalog()

    /** Starts the theme repository and its platform inputs (idempotent; ARCHITECTURE §10 step 2a). */
    fun startTheme() = themeRepository.start()

    /** Re-reads platform theme inputs (dynamic scheme, animator scale) after a configuration change or `onStart`. */
    fun refreshThemeInputs() = Unit

    /** Lets view-bound services (haptics) use the home window; null detaches. */
    fun attachWindow(view: View?) = Unit
}
