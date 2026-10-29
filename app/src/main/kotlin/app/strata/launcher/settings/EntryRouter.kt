package app.strata.launcher.settings

import android.content.Intent

/** How `SettingsActivity` was started. Only action and category are read; extras never are (ARCHITECTURE §13). */
enum class EntryKind {
    /** `MAIN` + `LAUNCHER`: the app icon in any launcher. */
    LAUNCHER_ICON,

    /** `APPLICATION_PREFERENCES`, or an explicit start from Strata itself. */
    SETTINGS,
}

enum class EntryRoute { ONBOARDING, HOME, SETTINGS_ROOT }

/** D-008 entry routing (FR-HOME-01, T-HOME-13). Pure, so it is unit-tested without Android. */
object EntryRouter {
    fun classify(action: String?, categories: Set<String>?): EntryKind =
        if (action == Intent.ACTION_MAIN && categories?.contains(Intent.CATEGORY_LAUNCHER) == true) {
            EntryKind.LAUNCHER_ICON
        } else {
            EntryKind.SETTINGS
        }

    fun route(kind: EntryKind, onboardingDone: Boolean): EntryRoute = when (kind) {
        EntryKind.SETTINGS -> EntryRoute.SETTINGS_ROOT
        EntryKind.LAUNCHER_ICON -> if (onboardingDone) EntryRoute.HOME else EntryRoute.ONBOARDING
    }
}
