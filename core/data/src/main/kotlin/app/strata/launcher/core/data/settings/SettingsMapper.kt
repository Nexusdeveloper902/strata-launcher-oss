package app.strata.launcher.core.data.settings

import app.strata.launcher.core.model.AppearanceSetting
import app.strata.launcher.core.model.BadgeStyle
import app.strata.launcher.core.model.OnboardingProgress
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.SwipeDownAction
import java.util.UUID
import app.strata.launcher.core.data.settings.AppearanceOverride as ProtoAppearance
import app.strata.launcher.core.data.settings.BadgeStyle as ProtoBadgeStyle
import app.strata.launcher.core.data.settings.OnboardingProgress as ProtoOnboarding
import app.strata.launcher.core.data.settings.Settings as ProtoSettings
import app.strata.launcher.core.data.settings.SwipeDownAction as ProtoSwipeDown

/**
 * Proto ↔ model mapping (ARCHITECTURE §4.2). An absent field reads as the catalogue default; values
 * outside their range read as the default (or are clamped). Writes touch only fields that changed, so
 * untouched fields stay absent and keep following future default changes.
 */
internal object SettingsMapper {
    fun toModel(p: ProtoSettings): Settings {
        val d = Settings.DEFAULT
        return Settings(
            catalogueVersion = if (p.hasCatalogueVersion()) p.catalogueVersion.coerceAtLeast(1) else d.catalogueVersion,
            installId = if (p.hasInstallId() && isUuidV4(p.installId)) p.installId else d.installId,
            onboardingDone = if (p.hasOnboardingDone()) p.onboardingDone else d.onboardingDone,
            listTwoColumn = if (p.hasListTwoColumn()) p.listTwoColumn else d.listTwoColumn,
            hapticsEnabled = if (p.hasHapticsEnabled()) p.hapticsEnabled else d.hapticsEnabled,
            showHiddenInSearch = if (p.hasShowHiddenInSearch()) p.showHiddenInSearch else d.showHiddenInSearch,
            recentsEnabled = if (p.hasRecentsEnabled()) p.recentsEnabled else d.recentsEnabled,
            launchLogPaused = if (p.hasLaunchLogPaused()) p.launchLogPaused else d.launchLogPaused,
            homePressOpensSearch = if (p.hasHomePressOpensSearch()) p.homePressOpensSearch else d.homePressOpensSearch,
            allowRotation = if (p.hasAllowRotation()) p.allowRotation else d.allowRotation,
            hideStatusBar = if (p.hasHideStatusBar()) p.hideStatusBar else d.hideStatusBar,
            keepBackGestureOnIndexSide =
                if (p.hasKeepBackGestureOnIndexSide()) p.keepBackGestureOnIndexSide else d.keepBackGestureOnIndexSide,
            badgesEnabled = if (p.hasBadgesEnabled()) p.badgesEnabled else d.badgesEnabled,
            badgeStyle = if (p.hasBadgeStyle()) badgeStyle(p.badgeStyle) else d.badgeStyle,
            contactsSearchEnabled =
                if (p.hasContactsSearchEnabled()) p.contactsSearchEnabled else d.contactsSearchEnabled,
            contactsHintShown = if (p.hasContactsHintShown()) p.contactsHintShown else d.contactsHintShown,
            appearanceOverride =
                if (p.hasAppearanceOverride()) {
                    appearance(
                        p.appearanceOverride,
                    )
                } else {
                    d.appearanceOverride
                },
            enforceContrast = if (p.hasEnforceContrast()) p.enforceContrast else d.enforceContrast,
            defaultHomeBannerDismissedUntil =
                if (p.hasDefaultHomeBannerDismissedUntil()) {
                    p.defaultHomeBannerDismissedUntil.coerceAtLeast(0L)
                } else {
                    d.defaultHomeBannerDismissedUntil
                },
            privateSpaceContainerHidden =
                if (p.hasPrivateSpaceContainerHidden()) {
                    p.privateSpaceContainerHidden
                } else {
                    d.privateSpaceContainerHidden
                },
            listSortByUsage = if (p.hasListSortByUsage()) p.listSortByUsage else d.listSortByUsage,
            swipeDownAction = if (p.hasSwipeDownAction()) swipeDown(p.swipeDownAction) else d.swipeDownAction,
            lockGestureEnabled = if (p.hasLockGestureEnabled()) p.lockGestureEnabled else d.lockGestureEnabled,
            onboardingProgress =
                if (p.hasOnboardingProgress()) onboarding(p.onboardingProgress) else d.onboardingProgress,
        )
    }

    /** Applies the fields that differ between [old] and [new] to [base]; equal fields are left as they are. */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun applyChanges(
        base: ProtoSettings,
        old: Settings,
        new: Settings,
    ): ProtoSettings {
        if (old == new) return base
        val b = base.toBuilder()
        if (old.catalogueVersion != new.catalogueVersion) b.catalogueVersion = new.catalogueVersion.coerceAtLeast(1)
        if (old.installId != new.installId) b.installId = new.installId
        if (old.onboardingDone != new.onboardingDone) b.onboardingDone = new.onboardingDone
        if (old.listTwoColumn != new.listTwoColumn) b.listTwoColumn = new.listTwoColumn
        if (old.hapticsEnabled != new.hapticsEnabled) b.hapticsEnabled = new.hapticsEnabled
        if (old.showHiddenInSearch != new.showHiddenInSearch) b.showHiddenInSearch = new.showHiddenInSearch
        if (old.recentsEnabled != new.recentsEnabled) b.recentsEnabled = new.recentsEnabled
        if (old.launchLogPaused != new.launchLogPaused) b.launchLogPaused = new.launchLogPaused
        if (old.homePressOpensSearch != new.homePressOpensSearch) b.homePressOpensSearch = new.homePressOpensSearch
        if (old.allowRotation != new.allowRotation) b.allowRotation = new.allowRotation
        if (old.hideStatusBar != new.hideStatusBar) b.hideStatusBar = new.hideStatusBar
        if (old.keepBackGestureOnIndexSide != new.keepBackGestureOnIndexSide) {
            b.keepBackGestureOnIndexSide = new.keepBackGestureOnIndexSide
        }
        if (old.badgesEnabled != new.badgesEnabled) b.badgesEnabled = new.badgesEnabled
        if (old.badgeStyle != new.badgeStyle) b.badgeStyle = proto(new.badgeStyle)
        if (old.contactsSearchEnabled != new.contactsSearchEnabled) b.contactsSearchEnabled = new.contactsSearchEnabled
        if (old.contactsHintShown != new.contactsHintShown) b.contactsHintShown = new.contactsHintShown
        if (old.appearanceOverride != new.appearanceOverride) b.appearanceOverride = proto(new.appearanceOverride)
        if (old.enforceContrast != new.enforceContrast) b.enforceContrast = new.enforceContrast
        if (old.defaultHomeBannerDismissedUntil != new.defaultHomeBannerDismissedUntil) {
            b.defaultHomeBannerDismissedUntil = new.defaultHomeBannerDismissedUntil.coerceAtLeast(0L)
        }
        if (old.privateSpaceContainerHidden != new.privateSpaceContainerHidden) {
            b.privateSpaceContainerHidden = new.privateSpaceContainerHidden
        }
        if (old.listSortByUsage != new.listSortByUsage) b.listSortByUsage = new.listSortByUsage
        if (old.swipeDownAction != new.swipeDownAction) b.swipeDownAction = proto(new.swipeDownAction)
        if (old.lockGestureEnabled != new.lockGestureEnabled) b.lockGestureEnabled = new.lockGestureEnabled
        if (old.onboardingProgress != new.onboardingProgress) b.onboardingProgress = proto(new.onboardingProgress)
        return b.build()
    }

    fun newInstallId(): String = UUID.randomUUID().toString()

    private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    fun isUuidV4(s: String): Boolean = UUID_V4.matches(s)

    private fun badgeStyle(p: ProtoBadgeStyle): BadgeStyle =
        when (p) {
            ProtoBadgeStyle.BADGE_STYLE_COUNT -> BadgeStyle.COUNT
            else -> BadgeStyle.DOT
        }

    private fun appearance(p: ProtoAppearance): AppearanceSetting =
        when (p) {
            ProtoAppearance.APPEARANCE_SYSTEM -> AppearanceSetting.SYSTEM
            ProtoAppearance.APPEARANCE_LIGHT -> AppearanceSetting.LIGHT
            ProtoAppearance.APPEARANCE_DARK -> AppearanceSetting.DARK
            else -> AppearanceSetting.FOLLOW_THEME
        }

    private fun swipeDown(p: ProtoSwipeDown): SwipeDownAction =
        when (p) {
            ProtoSwipeDown.SWIPE_DOWN_NOTIFICATIONS -> SwipeDownAction.NOTIFICATIONS
            else -> SwipeDownAction.NONE
        }

    private fun onboarding(p: ProtoOnboarding): OnboardingProgress =
        when (p) {
            ProtoOnboarding.ONBOARDING_FAVORITES -> OnboardingProgress.FAVORITES
            ProtoOnboarding.ONBOARDING_SET_DEFAULT -> OnboardingProgress.SET_DEFAULT
            ProtoOnboarding.ONBOARDING_ROLE_REQUESTED -> OnboardingProgress.ROLE_REQUESTED
            ProtoOnboarding.ONBOARDING_DONE -> OnboardingProgress.DONE
            else -> OnboardingProgress.WELCOME
        }

    private fun proto(v: OnboardingProgress): ProtoOnboarding =
        when (v) {
            OnboardingProgress.WELCOME -> ProtoOnboarding.ONBOARDING_WELCOME
            OnboardingProgress.FAVORITES -> ProtoOnboarding.ONBOARDING_FAVORITES
            OnboardingProgress.SET_DEFAULT -> ProtoOnboarding.ONBOARDING_SET_DEFAULT
            OnboardingProgress.ROLE_REQUESTED -> ProtoOnboarding.ONBOARDING_ROLE_REQUESTED
            OnboardingProgress.DONE -> ProtoOnboarding.ONBOARDING_DONE
        }

    private fun proto(v: BadgeStyle): ProtoBadgeStyle =
        when (v) {
            BadgeStyle.DOT -> ProtoBadgeStyle.BADGE_STYLE_DOT
            BadgeStyle.COUNT -> ProtoBadgeStyle.BADGE_STYLE_COUNT
        }

    private fun proto(v: AppearanceSetting): ProtoAppearance =
        when (v) {
            AppearanceSetting.FOLLOW_THEME -> ProtoAppearance.APPEARANCE_FOLLOW_THEME
            AppearanceSetting.SYSTEM -> ProtoAppearance.APPEARANCE_SYSTEM
            AppearanceSetting.LIGHT -> ProtoAppearance.APPEARANCE_LIGHT
            AppearanceSetting.DARK -> ProtoAppearance.APPEARANCE_DARK
        }

    private fun proto(v: SwipeDownAction): ProtoSwipeDown =
        when (v) {
            SwipeDownAction.NONE -> ProtoSwipeDown.SWIPE_DOWN_NONE
            SwipeDownAction.NOTIFICATIONS -> ProtoSwipeDown.SWIPE_DOWN_NOTIFICATIONS
        }
}
