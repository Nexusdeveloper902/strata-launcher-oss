package app.strata.launcher.core.platformapi.system

/**
 * Default-home status and the intents that change it (ARCHITECTURE §3; ANDROID_PLATFORM §2).
 * Implemented in `:platform:system`; faked in `:testing:fakes`.
 */
interface RoleController {
    /** True when Strata holds ROLE_HOME (29+) or is the preferred HOME activity (26–28). May do IPC: call off main. */
    fun isDefaultHome(): Boolean

    /** First intent to try: [SystemIntent.RequestHomeRole] on 29+ when the role is available, else home settings. */
    fun requestIntent(): SystemIntent

    /** Ordered settings chain to try when [requestIntent] cannot be started or was refused without UI. */
    fun fallbackIntents(): List<SystemIntent>

    /** The device build, for the OEM help card's known-blocking note (FR-ONBOARD-03, U-016). Cheap; main-safe. */
    fun deviceBuild(): DeviceBuild = DeviceBuild.UNKNOWN
}

/** `android.os.Build` fields the OEM help card reads (FR-ONBOARD-03). Never logged, stored or sent. */
data class DeviceBuild(
    val manufacturer: String,
    val brand: String,
    val fingerprint: String,
    val incremental: String,
) {
    companion object {
        val UNKNOWN = DeviceBuild("", "", "", "")
    }
}

/** Framework-free description of a system screen; `:platform:system` turns it into an `Intent`. */
sealed interface SystemIntent {
    /** `RoleManager.createRequestRoleIntent(ROLE_HOME)`, started for a result (API 29+). */
    data object RequestHomeRole : SystemIntent

    /** `Settings.ACTION_HOME_SETTINGS`. */
    data object HomeSettings : SystemIntent

    /** `Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS` (a matching activity may not exist). */
    data object DefaultAppsSettings : SystemIntent

    /** `Settings.ACTION_SETTINGS`, last resort. */
    data object Settings : SystemIntent
}
