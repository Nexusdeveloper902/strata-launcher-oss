package app.strata.launcher.feature.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import app.strata.launcher.core.data.list.AssembledAppListSource
import app.strata.launcher.core.model.Catalog
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.platformapi.apps.Profile
import app.strata.launcher.testing.fakes.FakeAppCatalog
import app.strata.launcher.testing.fakes.FakeAppLauncher
import app.strata.launcher.testing.fakes.FakeClock
import app.strata.launcher.testing.fakes.FakeCollation
import app.strata.launcher.testing.fakes.FakeLaunchLogRepository
import app.strata.launcher.testing.fakes.FakeOverridesRepository
import app.strata.launcher.testing.fakes.FakeProfileSource
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fakes.FakeSystemIntents
import app.strata.launcher.testing.fakes.FakeTextFolding
import app.strata.launcher.testing.fixtures.AppFixtures.app
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FR-SEARCH-03 private part and FR-APPS-09 in search, over the real list pipeline ([AssembledAppListSource]) with the
 * profile state of [FakeProfileSource] (level R).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PrivateSpaceSearchTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val pocket = app("Pocket vault", serial = PRIVATE, kind = ProfileKind.PRIVATE)
    private val notes = app("Notes")
    private val mail = app("Mail", serial = WORK)
    private val profiles =
        FakeProfileSource(
            listOf(
                Profile(0, ProfileKind.MAIN, quietMode = false, available = true),
                Profile(WORK, ProfileKind.WORK, quietMode = false, available = true),
                Profile(PRIVATE, ProfileKind.PRIVATE, quietMode = true, available = false),
            ),
        )
    private val settings = FakeSettingsRepository(Settings(onboardingDone = true))
    private val launchLog = FakeLaunchLogRepository(FakeClock())
    private val launcher = FakeAppLauncher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): SearchViewModel {
        val appList =
            AssembledAppListSource(
                // The catalog still holds the private entry, as right after a lock before its reload: the profile state
                // alone must hide it (T-LIFE-09 "immediately").
                catalog = FakeAppCatalog(Catalog(listOf(notes, mail, pocket), fromSnapshot = false)),
                overrides = FakeOverridesRepository().overrides,
                locales = MutableStateFlow(listOf("en")),
                collation = FakeCollation(),
                defaultDispatcher = Dispatchers.Unconfined,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                profiles = profiles.profiles,
            )
        return SearchViewModel(
            appList = appList,
            settings = settings,
            launchLog = launchLog,
            launcher = launcher,
            catalog = FakeAppCatalog(),
            textFolding = FakeTextFolding(),
            systemIntents = FakeSystemIntents(),
            defaultDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )
    }

    private fun SearchViewModel.labels() = state.value.results.map { it.displayLabel }

    /**
     * T-SEARCH-08 (R) and T-LIFE-09 (R): locked → 0 results for P; unlocked → P, marked private; locked again while the
     * results show → gone at once (before any catalog reload). Nothing reaches the launch log.
     */
    @Test
    fun tSearch08_tLife09_lockedPrivateNeverSearchable() {
        val vm = viewModel()
        vm.onAction(SearchAction.Open("pock"))
        assertThat(vm.labels()).isEmpty()
        profiles.setQuiet(PRIVATE, false)
        assertThat(vm.labels()).containsExactly("Pocket vault")
        assertThat(
            vm.state.value.results
                .single()
                .isPrivate,
        ).isTrue()
        profiles.setQuiet(PRIVATE, true)
        assertThat(vm.labels()).isEmpty()
        assertThat(launchLog.events).isEmpty()
    }

    /** FR-APPS-10: a hidden container (settings field 20) hides its apps from search too. */
    @Test
    fun hiddenContainer_notSearchable() {
        profiles.setQuiet(PRIVATE, false)
        val vm = viewModel()
        vm.onAction(SearchAction.Open("pock"))
        assertThat(vm.labels()).containsExactly("Pocket vault")
        settings.set(Settings(onboardingDone = true, privateSpaceContainerHidden = true))
        assertThat(vm.labels()).isEmpty()
    }

    /** FR-APPS-09: a paused work app in search is handed to home ("Turn on work apps?"), not launched. */
    @Test
    fun pausedWorkApp_handedToHome() =
        runTest(dispatcher) {
            profiles.setQuiet(WORK, true)
            val vm = viewModel()
            vm.onAction(SearchAction.Open("mai"))
            val result =
                vm.state.value.results
                    .single()
            assertThat(result.paused).isTrue()
            vm.effects.test {
                vm.onAction(SearchAction.LaunchSelected)
                assertThat(awaitItem()).isEqualTo(SearchEffect.ProfilePaused(result))
            }
            assertThat(launcher.launches).isEmpty()
        }

    private companion object {
        const val WORK = 10L
        const val PRIVATE = 99L
    }
}
