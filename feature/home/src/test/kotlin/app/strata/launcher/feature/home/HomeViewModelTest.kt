package app.strata.launcher.feature.home

import app.cash.turbine.test
import app.strata.launcher.core.common.log.LogLevel
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.apps.LaunchError
import app.strata.launcher.core.platformapi.apps.OverridesRepository
import app.strata.launcher.core.platformapi.settings.ResetCause
import app.strata.launcher.core.platformapi.system.HapticKind
import app.strata.launcher.testing.fakes.FakeAppCatalog
import app.strata.launcher.testing.fakes.FakeAppLauncher
import app.strata.launcher.testing.fakes.FakeAppListSource
import app.strata.launcher.testing.fakes.FakeClock
import app.strata.launcher.testing.fakes.FakeFavoritesRepository
import app.strata.launcher.testing.fakes.FakeHaptics
import app.strata.launcher.testing.fakes.FakeLaunchLogRepository
import app.strata.launcher.testing.fakes.FakeLockService
import app.strata.launcher.testing.fakes.FakeOverridesRepository
import app.strata.launcher.testing.fakes.FakeResetNotices
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fakes.FakeSystemIntents
import app.strata.launcher.testing.fakes.RecordingLogger
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

    private val logger = RecordingLogger()

    private fun viewModel(
        overrides: OverridesRepository = FakeOverridesRepository(),
        lockService: FakeLockService = FakeLockService(),
    ) =
        HomeViewModel(
            role,
            settings,
            FakeAppListSource(),
            catalog,
            launcher,
            log,
            haptics,
            resets,
            dispatcher,
            overrides,
            FakeFavoritesRepository(),
            FakeSystemIntents(),
            lockService,
            logger,
        )

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

    /** A failing Room write is logged and reported as an effect instead of crashing the launcher. */
    @Test
    fun failedWriteIsLoggedAndReported() =
        runTest(dispatcher) {
            val failing =
                object : OverridesRepository by FakeOverridesRepository() {
                    override suspend fun setHidden(
                        key: AppKey,
                        profileKind: ProfileKind,
                        hidden: Boolean,
                    ) = throw IllegalStateException("disk full")
                }
            val vm = viewModel(overrides = failing)
            vm.effects.test {
                vm.onAction(HomeAction.SetHidden(app.key, hidden = true))
                assertThat(awaitItem()).isEqualTo(HomeEffect.WriteFailed)
            }
            assertThat(logger.entries.map { it.level }).contains(LogLevel.WARN)
        }

    /** A sheet whose app cannot be resolved is not a modal (it would draw nothing and block Back). */
    @Test
    fun sheetWithoutAppIsNoModal() {
        val vm = viewModel()
        vm.onAction(HomeAction.OpenSheet(app))
        assertThat(vm.state.value.modal).isEqualTo(HomeModal.None)
    }

    /** A letter jump does not drop an Edit-favourites session. */
    @Test
    fun jumpIgnoredInFavEdit() {
        val vm = viewModel()
        vm.onAction(HomeAction.EditFavorites)
        vm.onAction(HomeAction.JumpToLetter(3))
        assertThat(vm.state.value.layer).isEqualTo(HomeLayer.FAV_EDIT)
        assertThat(vm.state.value.listJump).isNull()
    }

    /** Service enabled in the system but not connected yet: no "turn on in settings" hint; disabled: hint. */
    @Test
    fun lockHintOnlyWhenServiceIsOff() =
        runTest(dispatcher) {
            val pending = viewModel(lockService = FakeLockService(enabled = true, connected = false))
            pending.effects.test {
                pending.onAction(HomeAction.LockScreen)
                expectNoEvents()
            }
            val off = viewModel(lockService = FakeLockService(enabled = false))
            off.effects.test {
                off.onAction(HomeAction.LockScreen)
                assertThat(awaitItem()).isEqualTo(HomeEffect.LockHint)
            }
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

    /** INTERACTION_SPEC §3: SEARCH(origin) returns to its origin; each open is a new session with its seed. */
    @Test
    fun searchLayerReturnsToOrigin() {
        val vm = viewModel()
        vm.onAction(HomeAction.OpenSearch("m"))
        val first = vm.state.value.search
        assertThat(vm.state.value.layer).isEqualTo(HomeLayer.SEARCH)
        assertThat(first?.origin).isEqualTo(HomeLayer.REST)
        assertThat(first?.seed).isEqualTo("m")
        vm.onAction(HomeAction.CloseSearch)
        assertThat(vm.state.value.layer).isEqualTo(HomeLayer.REST)
        assertThat(vm.state.value.search).isNull()

        vm.onAction(HomeAction.OpenList)
        vm.onAction(HomeAction.OpenSearch())
        val second = vm.state.value.search
        assertThat(second?.origin).isEqualTo(HomeLayer.LIST)
        assertThat(second?.session).isNotEqualTo(first?.session)
        vm.onAction(HomeAction.CloseSearch)
        assertThat(vm.state.value.layer).isEqualTo(HomeLayer.LIST)
    }

    /** INTERACTION_SPEC §3/§6: Home from SEARCH(list) goes to HOME_REST and clears the search. */
    @Test
    fun homePressedClosesSearch() {
        val vm = viewModel()
        vm.onAction(HomeAction.OpenList)
        vm.onAction(HomeAction.OpenSearch("ma"))
        vm.onAction(HomeAction.HomePressed)
        assertThat(vm.state.value.layer).isEqualTo(HomeLayer.REST)
        assertThat(vm.state.value.search).isNull()
    }

    /** FR-HOME-05: "Home press opens search" (default off) opens search only from rest. */
    @Test
    fun homePressOpensSearchSetting() {
        val vm = viewModel()
        vm.onAction(HomeAction.HomePressed)
        assertThat(vm.state.value.layer).isEqualTo(HomeLayer.REST)
        settings.set(Settings(onboardingDone = true, homePressOpensSearch = true))
        vm.onAction(HomeAction.HomePressed)
        assertThat(vm.state.value.layer).isEqualTo(HomeLayer.SEARCH)
        vm.onAction(HomeAction.HomePressed)
        assertThat(vm.state.value.layer).isEqualTo(HomeLayer.REST)
    }

    /** INTERACTION_SPEC §5: printable keys only, digits included, no chords or whitespace. */
    @Test
    fun printableKeys() {
        assertThat(HomeKeys.printable('m'.code, ctrl = false, alt = false, meta = false)).isEqualTo("m")
        assertThat(HomeKeys.printable('7'.code, ctrl = false, alt = false, meta = false)).isEqualTo("7")
        assertThat(HomeKeys.printable('é'.code, ctrl = false, alt = false, meta = false)).isEqualTo("é")
        assertThat(HomeKeys.printable('m'.code, ctrl = true, alt = false, meta = false)).isNull()
        assertThat(HomeKeys.printable(' '.code, ctrl = false, alt = false, meta = false)).isNull()
        assertThat(HomeKeys.printable('\t'.code, ctrl = false, alt = false, meta = false)).isNull()
        assertThat(HomeKeys.printable(0, ctrl = false, alt = false, meta = false)).isNull()
        assertThat(HomeKeys.printable(-0x7fffffff, ctrl = false, alt = false, meta = false)).isNull()
    }

    private fun bannerViewModel(clock: FakeClock) =
        HomeViewModel(
            role,
            settings,
            FakeAppListSource(),
            catalog,
            launcher,
            log,
            haptics,
            resets,
            dispatcher,
            FakeOverridesRepository(),
            FakeFavoritesRepository(),
            FakeSystemIntents(),
            FakeLockService(),
            logger,
            clock = clock,
        )

    /**
     * T-ONBOARD-08 (R half) and T-LIFE-10 (banner half): the default is lost while Strata runs → "no longer your home
     * app" on resume. Dismiss hides it for 24 h (FakeClock); after 24 h it is back.
     */
    @Test
    fun tOnboard08_lostDefaultBanner_snoozedFor24h() =
        runTest(dispatcher) {
            val clock = FakeClock(now = 1_000_000L)
            role.defaultHome = true
            val vm = bannerViewModel(clock)
            vm.onAction(HomeAction.RefreshRole)
            assertThat(vm.state.value.defaultBanner).isNull()
            role.defaultHome = false // switched in Settings / `cmd role`
            vm.onAction(HomeAction.RefreshRole)
            assertThat(vm.state.value.defaultBanner).isEqualTo(DefaultHomeBanner.LOST)

            vm.onAction(HomeAction.DismissDefaultBanner)
            assertThat(settings.value.defaultHomeBannerDismissedUntil).isEqualTo(clock.now + BANNER_SNOOZE_MS)
            assertThat(vm.state.value.showAppModeBanner).isFalse()
            clock.advanceBy(BANNER_SNOOZE_MS - 60_000) // reopened within 24 h
            vm.onAction(HomeAction.RefreshRole)
            assertThat(vm.state.value.showAppModeBanner).isFalse()
            clock.advanceBy(120_000) // after 24 h
            vm.onAction(HomeAction.RefreshRole)
            assertThat(vm.state.value.defaultBanner).isEqualTo(DefaultHomeBanner.LOST)
        }

    /** T-ONBOARD-06 (R half): after "Not now" the app-mode banner shows; never during onboarding itself. */
    @Test
    fun tOnboard06_bannerAfterNotNow_notDuringOnboarding() =
        runTest(dispatcher) {
            settings.set(Settings(onboardingDone = false))
            val vm = bannerViewModel(FakeClock(now = 5_000L))
            vm.onAction(HomeAction.RefreshRole)
            assertThat(vm.state.value.showAppModeBanner).isFalse()
            settings.set(Settings(onboardingDone = true)) // "Not now"
            assertThat(vm.state.value.defaultBanner).isEqualTo(DefaultHomeBanner.NOT_DEFAULT)
        }
}
