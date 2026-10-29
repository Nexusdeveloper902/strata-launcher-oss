package app.strata.launcher.testing.fakes

import app.strata.launcher.core.platformapi.settings.OnboardingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory [OnboardingStore]. */
class FakeOnboardingStore(initial: Boolean = false) : OnboardingStore {
    private val state = MutableStateFlow(initial)

    override val onboardingDone: StateFlow<Boolean> = state

    val value: Boolean get() = state.value

    override suspend fun setOnboardingDone(done: Boolean) {
        state.value = done
    }
}
