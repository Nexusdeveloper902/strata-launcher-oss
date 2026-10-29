package app.strata.launcher.feature.home

import app.cash.turbine.test
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.apps.LaunchError
import app.strata.launcher.core.platformapi.settings.ResetCause
import app.strata.launcher.core.platformapi.system.HapticKind
import app.strata.launcher.testing.fakes.FakeAppCatalog
import app.strata.launcher.testing.fakes.FakeAppLauncher
import app.strata.launcher.testing.fakes.FakeAppListSource
import app.strata.launcher.testing.fakes.FakeHaptics
import app.strata.launcher.testing.fakes.FakeLaunchLogRepository
import app.strata.launcher.testing.fakes.FakeResetNotices
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
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
    private val settings = FakeSettingsRepository(Settings(onboardingDone = true))
    private val catalog = FakeAppCatalog()
    private val launcher = FakeAppLauncher()
    private val log = FakeLaunchLogRepository()
    private val haptics = FakeHaptics()
    private val resets = FakeResetNotices()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() =
        HomeViewModel(role, settings, FakeAppListSource(), catalog, launcher, log, haptics, resets, dispatcher)

    private val app =
        VisibleApp(
            key = AppKey("p/p.Main", 0),
            packageName = "p",
            displayLabel = "Zz Test",
            systemLabel = "Zz Test",
            sectionKey = "Z",
            profileKind = ProfileKind.MAIN,
            hidden = false,
        )

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

    /** D-008 / T-ONBOARD-09 (stub): onboarding is the first layer until Continue sets field 3. */
    @Test
    fun onboardingLayerUntilCompleted() =
        runTest(dispatcher) {
            settings.set(Settings(onboardingDone = false))
            val vm = viewModel()
            assertThat(vm.state.value.showOnboarding).isTrue()
            vm.onAction(HomeAction.CompleteOnboarding)
            assertThat(settings.value.onboardingDone).isTrue()
            assertThat(vm.state.value.showOnboarding).isFalse()
        }

    /** INTERACTION_SPEC §6: Home pressed while in front closes the list and returns to rest. */
    @Test
    fun homePressedReturnsToRest() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.onAction(HomeAction.OpenList)
            assertThat(vm.state.value.layer).isEqualTo(HomeLayer.LIST)
            vm.effects.test {
                vm.onAction(HomeAction.HomePressed)
                assertThat(awaitItem()).isEqualTo(HomeEffect.ReturnToRest)
            }
            assertThat(vm.state.value.layer).isEqualTo(HomeLayer.REST)
        }

    /** FR-APPS-02: a successful launch passes the key and writes the launch log. */
    @Test
    fun launchRecordsLog() {
        val vm = viewModel()
        vm.onAction(HomeAction.Launch(app, null))
        assertThat(launcher.launches.map { it.first }).containsExactly(app.key)
        assertThat(log.events.map { it.first }).containsExactly(app.key)
        assertThat(catalog.reconcileRequests).isEqualTo(0)
    }

    /** T-APPS-03 (VM half): a failed launch shows the message and reconciles; no log row. */
    @Test
    fun tApps03_launchFailureShowsMessageAndReconciles() =
        runTest(dispatcher) {
            launcher.failing += app.key
            val vm = viewModel()
            vm.effects.test {
                vm.onAction(HomeAction.Launch(app, null))
                assertThat(awaitItem()).isEqualTo(HomeEffect.LaunchFailed("Zz Test", app.key, LaunchError.NOT_FOUND))
            }
            assertThat(catalog.reconcileRequests).isEqualTo(1)
            assertThat(log.events).isEmpty()
        }

    /** T-LIST-04 (VM half): ticks on letter changes only; none with the master switch off (FR-SET-05). */
    @Test
    fun tList04_ticksFollowMasterSwitch() {
        val vm = viewModel()
        vm.onAction(HomeAction.IndexScrub(first = true))
        repeat(25) { vm.onAction(HomeAction.IndexScrub(first = false)) }
        assertThat(haptics.count(HapticKind.TICK)).isEqualTo(25)
        settings.set(Settings(onboardingDone = true, hapticsEnabled = false))
        repeat(25) { vm.onAction(HomeAction.IndexScrub(first = false)) }
        assertThat(haptics.count(HapticKind.TICK)).isEqualTo(25)
    }

    /** FR-LIFE-04: a reported reset shows the banner until dismissed. */
    @Test
    fun resetBanner() {
        val vm = viewModel()
        assertThat(vm.state.value.showResetBanner).isFalse()
        resets.report(ResetCause.DATABASE_CORRUPT)
        assertThat(vm.state.value.showResetBanner).isTrue()
        vm.onAction(HomeAction.DismissResetBanner)
        assertThat(vm.state.value.showResetBanner).isFalse()
    }
}
