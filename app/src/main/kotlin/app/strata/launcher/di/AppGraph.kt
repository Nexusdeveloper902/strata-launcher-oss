package app.strata.launcher.di

import app.strata.launcher.core.common.AppDispatchers
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.platformapi.settings.OnboardingStore
import app.strata.launcher.core.platformapi.system.RoleController
import kotlinx.coroutines.CoroutineScope

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
    val onboardingStore: OnboardingStore
}
