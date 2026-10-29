package app.strata.launcher.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strata.launcher.core.platformapi.settings.OnboardingStore
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

/** Home state for M0: placeholder content, APP_MODE banner, onboarding stub layer. */
data class HomeUiState(
    /** `null` until the stored flag has been read. */
    val onboardingDone: Boolean? = null,
    /** `null` until the first role check has finished. */
    val isDefaultHome: Boolean? = null,
) {
    /** INTERACTION_SPEC APP_MODE: same UI plus a banner while the role is not held (FR-ONBOARD-02). */
    val showAppModeBanner: Boolean get() = isDefaultHome == false

    /** D-008: onboarding is the first layer of home until `onboarding_done` is set. */
    val showOnboarding: Boolean get() = onboardingDone == false
}

sealed interface HomeAction {
    /** `onResume`: the user may have changed the home app in Settings (FR-ONBOARD-04). */
    data object RefreshRole : HomeAction

    /** `onNewIntent(MAIN + HOME)` while home is in front (ANDROID_PLATFORM §1). */
    data object HomePressed : HomeAction

    /** "Continue" on the onboarding stub. */
    data object CompleteOnboarding : HomeAction
}

sealed interface HomeEffect {
    /** Collapse open layers and return to HOME_REST (layers arrive in M2/M3). */
    data object ReturnToRest : HomeEffect
}

class HomeViewModel(
    private val roleController: RoleController,
    private val onboardingStore: OnboardingStore,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val isDefaultHome = MutableStateFlow<Boolean?>(null)
    private val effectChannel = Channel<HomeEffect>(Channel.BUFFERED)

    val effects: Flow<HomeEffect> = effectChannel.receiveAsFlow()

    val state: StateFlow<HomeUiState> =
        combine(
            onboardingStore.onboardingDone.map<Boolean, Boolean?> { it }.onStart { emit(null) },
            isDefaultHome,
        ) { onboarded, isDefault -> HomeUiState(onboardingDone = onboarded, isDefaultHome = isDefault) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.RefreshRole -> {
                viewModelScope.launch {
                    isDefaultHome.value = withContext(ioDispatcher) { roleController.isDefaultHome() }
                }
            }

            HomeAction.HomePressed -> {
                effectChannel.trySend(HomeEffect.ReturnToRest)
            }

            HomeAction.CompleteOnboarding -> {
                viewModelScope.launch {
                    onboardingStore.setOnboardingDone(true)
                }
            }
        }
    }
}
