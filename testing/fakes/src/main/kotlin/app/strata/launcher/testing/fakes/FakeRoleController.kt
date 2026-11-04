package app.strata.launcher.testing.fakes

import app.strata.launcher.core.platformapi.system.DeviceBuild
import app.strata.launcher.core.platformapi.system.RoleController
import app.strata.launcher.core.platformapi.system.SystemIntent

/** Test double for [RoleController]. Flip [defaultHome] to simulate the user changing the home app. */
class FakeRoleController(
    @Volatile var defaultHome: Boolean = false,
    var request: SystemIntent = SystemIntent.RequestHomeRole,
    var fallbacks: List<SystemIntent> =
        listOf(
            SystemIntent.HomeSettings,
            SystemIntent.DefaultAppsSettings,
            SystemIntent.Settings,
        ),
    var device: DeviceBuild = DeviceBuild.UNKNOWN,
) : RoleController {
    var isDefaultHomeCalls: Int = 0
        private set

    override fun isDefaultHome(): Boolean {
        isDefaultHomeCalls++
        return defaultHome
    }

    override fun requestIntent(): SystemIntent = request

    override fun fallbackIntents(): List<SystemIntent> = fallbacks

    override fun deviceBuild(): DeviceBuild = device
}
