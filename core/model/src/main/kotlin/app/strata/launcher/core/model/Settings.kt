package app.strata.launcher.core.model

enum class BadgeStyle { DOT, COUNT }

/** ARCHITECTURE §4.2 field 17 (the theme engine has its own copy for its pure inputs). */
enum class AppearanceSetting { FOLLOW_THEME, SYSTEM, LIGHT, DARK }

enum class SwipeDownAction { NONE, NOTIFICATIONS }

/**
 * Global settings with the catalogue defaults applied (ARCHITECTURE §4.2). Only `SettingsRepository`
 * reads the proto; everything else sees this model. Field order = proto field numbers.
 */
data class Settings(
    val catalogueVersion: Int = CATALOGUE_VERSION,
    val installId: String = "",
    val onboardingDone: Boolean = false,
    val listTwoColumn: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val showHiddenInSearch: Boolean = true,
    val recentsEnabled: Boolean = true,
    val launchLogPaused: Boolean = false,
    val homePressOpensSearch: Boolean = false,
    val allowRotation: Boolean = false,
    val hideStatusBar: Boolean = false,
    val keepBackGestureOnIndexSide: Boolean = false,
    val badgesEnabled: Boolean = false,
    val badgeStyle: BadgeStyle = BadgeStyle.DOT,
    val contactsSearchEnabled: Boolean = false,
    val contactsHintShown: Boolean = false,
    val appearanceOverride: AppearanceSetting = AppearanceSetting.FOLLOW_THEME,
    val enforceContrast: Boolean = true,
    val defaultHomeBannerDismissedUntil: Long = 0L,
    val privateSpaceContainerHidden: Boolean = false,
    val listSortByUsage: Boolean = false,
    val swipeDownAction: SwipeDownAction = SwipeDownAction.NONE,
    val lockGestureEnabled: Boolean = false,
) {
    companion object {
        /** Current catalogue version (field 1). */
        const val CATALOGUE_VERSION: Int = 1
        val DEFAULT = Settings()
    }
}
