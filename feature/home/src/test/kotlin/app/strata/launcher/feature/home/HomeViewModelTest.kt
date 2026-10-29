package app.strata.launcher.feature.home

import app.cash.turbine.test
import app.strata.launcher.testing.fakes.FakeOnboardingStore
import app.strata.launcher.testing.fakes.FakeRoleController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val role = FakeRoleController(defaultHome = false)
    private val store = FakeOnboardingStore(initial = true)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = HomeViewModel(role, store, dispatcher)

    /** T-HOME-02 (banner stub): APP_MODE banner shown only while the role is not held. */
    @Test
    fun tHome02_bannerFollowsRoleOnResume() {
        val vm = viewModel()
        assertThat(vm.state.value.showAppModeBanner).isFalse() // unknown before the first check
        vm.onAction(HomeAction.RefreshRole)
        assertThat(vm.state.value.showAppModeBanner).isTrue()
        role.defaultHome = true
        vm.onAction(HomeAction.RefreshRole)
        assertThat(vm.state.value.showAppModeBanner).isFalse()
    }

    /** D-008 / T-ONBOARD-09 (stub): onboarding is the first layer until Continue sets the flag. */
    @Test
    fun onboardingLayerUntilCompleted() =
        runTest(dispatcher) {
            store.setOnboardingDone(false)
            val vm = viewModel()
            assertThat(vm.state.value.showOnboarding).isTrue()
            vm.onAction(HomeAction.CompleteOnboarding)
            assertThat(store.value).isTrue()
            assertThat(vm.state.value.showOnboarding).isFalse()
        }

    /** ANDROID_PLATFORM §1: Home pressed while in front → return to rest. */
    @Test
    fun homePressedEmitsReturnToRest() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.effects.test {
                vm.onAction(HomeAction.HomePressed)
                assertThat(awaitItem()).isEqualTo(HomeEffect.ReturnToRest)
            }
        }
}
