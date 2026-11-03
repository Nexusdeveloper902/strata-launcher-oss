package app.strata.launcher.di

import android.view.View
import app.strata.launcher.core.common.AppDispatchers
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.platformapi.apps.AppCatalog
import app.strata.launcher.core.platformapi.apps.AppLauncher
import app.strata.launcher.core.platformapi.apps.AppListSource
import app.strata.launcher.core.platformapi.apps.LaunchLogRepository
import app.strata.launcher.core.platformapi.settings.ResetNotices
import app.strata.launcher.core.platformapi.settings.SettingsRepository
import app.strata.launcher.core.platformapi.system.Haptics
import app.strata.launcher.core.platformapi.system.RoleController
import app.strata.launcher.core.platformapi.system.SystemIntents
import app.strata.launcher.core.platformapi.system.TextFolding
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

    /** BCP-47 tags of the current configuration's locales (sections, collation). */
    val locales: MutableStateFlow<List<String>>

    /** Reads the snapshot and subscribes to package changes (idempotent; ARCHITECTURE §10 step 2b). */
    fun startCatalog()

    /** Lets view-bound services (haptics) use the home window; null detaches. */
    fun attachWindow(view: View?) = Unit
}
