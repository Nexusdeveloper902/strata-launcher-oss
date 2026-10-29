package app.strata.launcher.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.BadgeStyle
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.apps.AppCatalog
import app.strata.launcher.core.platformapi.apps.AppLauncher
import app.strata.launcher.core.platformapi.apps.AppListSource
import app.strata.launcher.core.platformapi.apps.LaunchError
import app.strata.launcher.core.platformapi.apps.LaunchLogRepository
import app.strata.launcher.core.platformapi.apps.LaunchOrigin
import app.strata.launcher.core.platformapi.settings.ResetNotices
import app.strata.launcher.core.platformapi.settings.SettingsRepository
import app.strata.launcher.core.platformapi.system.HapticKind
import app.strata.launcher.core.platformapi.system.Haptics
import app.strata.launcher.core.platformapi.system.RoleController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** INTERACTION_SPEC §3 layers built so far (search, sheet and FAVEDIT arrive in M2/M3). */
enum class HomeLayer { REST, LIST }

data class HomeUiState(
    /** `null` until the stored flag has been read. */
    val onboardingDone: Boolean? = null,
    /** `null` until the first role check has finished. */
    val isDefaultHome: Boolean? = null,
    val layer: HomeLayer = HomeLayer.REST,
    /** `null` until the first list (snapshot) is assembled. */
    val list: AppList? = null,
    val listTwoColumn: Boolean = true,
    val badgeStyle: BadgeStyle = BadgeStyle.DOT,
    /** FR-LIFE-04 "Some settings were reset". */
    val showResetBanner: Boolean = false,
) {
    /** INTERACTION_SPEC APP_MODE: same UI plus a banner while the role is not held (FR-ONBOARD-02). */
    val showAppModeBanner: Boolean get() = isDefaultHome == false

    /** D-008: onboarding is the first layer of home until `onboarding_done` is set. */
    val showOnboarding: Boolean get() = onboardingDone == false

    /** The first list frame can be drawn (snapshot or later). */
    val listReady: Boolean get() = list != null
}

sealed interface HomeAction {
    /** `onResume`: the user may have changed the home app in Settings (FR-ONBOARD-04). */
    data object RefreshRole : HomeAction

    /** `onNewIntent(MAIN + HOME)` while home is in front (ANDROID_PLATFORM §1, INTERACTION_SPEC §6). */
    data object HomePressed : HomeAction

    /** "Continue" on the onboarding stub. */
    data object CompleteOnboarding : HomeAction

    /** "All apps" row (INTERACTION_SPEC §3 HOME_REST → LIST). */
    data object OpenList : HomeAction

    /** Back from the list. */
    data object CloseList : HomeAction

    data class Launch(val app: VisibleApp, val origin: LaunchOrigin?) : HomeAction

    data class OpenAppInfo(val key: AppKey, val origin: LaunchOrigin?) : HomeAction

    /** The index jumped to a letter; [first] = touch-down (no tick), else a letter change (tick). */
    data class IndexScrub(val first: Boolean) : HomeAction

    data object OpenSettings : HomeAction

    data object OpenHiddenApps : HomeAction

    data object DismissResetBanner : HomeAction
}

sealed interface HomeEffect {
    /** Collapse open layers and return to HOME_REST. */
    data object ReturnToRest : HomeEffect

    /** INTERACTION_SPEC §8 launch failure: snackbar with an App info action. */
    data class LaunchFailed(val label: String, val key: AppKey, val error: LaunchError) : HomeEffect

    data object OpenSettings : HomeEffect

    data object OpenHiddenApps : HomeEffect
}

class HomeViewModel(
    private val roleController: RoleController,
    private val settings: SettingsRepository,
    appList: AppListSource,
    private val catalog: AppCatalog,
    private val launcher: AppLauncher,
    private val launchLog: LaunchLogRepository,
    private val haptics: Haptics,
    private val resetNotices: ResetNotices,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val isDefaultHome = MutableStateFlow<Boolean?>(null)
    private val layer = MutableStateFlow(HomeLayer.REST)
    private val effectChannel = Channel<HomeEffect>(Channel.BUFFERED)

    val effects: Flow<HomeEffect> = effectChannel.receiveAsFlow()

    private val currentSettings: StateFlow<Settings?> =
        settings.settings
            .map<Settings, Settings?> { it }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<HomeUiState> =
        combine(
            currentSettings,
            appList.appList.map<AppList, AppList?> { it }.onStart { emit(null) },
            isDefaultHome,
            layer,
            resetNotices.causes,
        ) { s, list, isDefault, l, resets ->
            HomeUiState(
                onboardingDone = s?.onboardingDone,
                isDefaultHome = isDefault,
                layer = l,
                list = list,
                listTwoColumn = s?.listTwoColumn ?: true,
                badgeStyle = s?.badgeStyle ?: BadgeStyle.DOT,
                showResetBanner = resets.isNotEmpty(),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.RefreshRole -> {
                viewModelScope.launch {
                    isDefaultHome.value = withContext(ioDispatcher) { roleController.isDefaultHome() }
                }
            }

            HomeAction.HomePressed -> {
                layer.value = HomeLayer.REST
                effectChannel.trySend(HomeEffect.ReturnToRest)
            }

            HomeAction.CompleteOnboarding -> {
                viewModelScope.launch { settings.update { it.copy(onboardingDone = true) } }
            }

            HomeAction.OpenList -> {
                layer.value = HomeLayer.LIST
            }

            HomeAction.CloseList -> {
                layer.value = HomeLayer.REST
            }

            is HomeAction.Launch -> {
                launch(action.app, action.origin)
            }

            is HomeAction.OpenAppInfo -> {
                launcher.openAppInfo(action.key, action.origin)
            }

            is HomeAction.IndexScrub -> {
                // One tick per letter change, none on touch-down; the master switch wins (FR-SET-05, FR-THEME-17).
                if (!action.first && currentSettings.value?.hapticsEnabled != false) haptics.tick(HapticKind.TICK)
            }

            HomeAction.OpenSettings -> {
                effectChannel.trySend(HomeEffect.OpenSettings)
            }

            HomeAction.OpenHiddenApps -> {
                effectChannel.trySend(HomeEffect.OpenHiddenApps)
            }

            HomeAction.DismissResetBanner -> {
                resetNotices.dismiss()
            }
        }
    }

    /**
     * FR-APPS-02: start synchronously on main (startActivity is cheap, ARCHITECTURE §5) and record the
     * launch asynchronously. A failure shows the message and reconciles (FR-LIFE-03).
     */
    private fun launch(
        app: VisibleApp,
        origin: LaunchOrigin?,
    ) {
        when (val result = launcher.launch(app.key, origin)) {
            is Outcome.Ok -> {
                viewModelScope.launch(ioDispatcher) {
                    // The log is disposable: a storage failure must never surface (degrade, never die).
                    runCatching { launchLog.record(app.key, app.profileKind) }
                }
            }

            is Outcome.Err -> {
                effectChannel.trySend(HomeEffect.LaunchFailed(app.displayLabel, app.key, result.error))
                catalog.requestReconcile()
            }
        }
    }
}
