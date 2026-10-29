package app.strata.launcher.core.platformapi.settings

import kotlinx.coroutines.flow.Flow

/**
 * The `onboarding_done` flag (ARCHITECTURE §4.2 field 3; D-008).
 *
 * M0 keeps it in a temporary DataStore file owned by `:core:data`; M1 migrates the value into
 * `settings.pb` and replaces this interface with `SettingsRepository`.
 */
interface OnboardingStore {
    /** Emits the current value, then every change. Defaults to `false`. */
    val onboardingDone: Flow<Boolean>

    suspend fun setOnboardingDone(done: Boolean)
}
