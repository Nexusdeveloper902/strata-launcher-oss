package app.strata.launcher.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.AppSection
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.testing.fakes.FakeAppListSource
import app.strata.launcher.testing.fakes.FakeFavoritesRepository
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
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

/** FR-ONBOARD-01/02 flow logic (level U/R). */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val role = FakeRoleController(defaultHome = false)
    private val settings = FakeSettingsRepository(Settings(onboardingDone = false))
    private val favorites = FakeFavoritesRepository()
    private val section = onboardingApps(15)
    private val apps = section.apps
    private val appList = FakeAppListSource(listOf(section))

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) =
        OnboardingViewModel(appList, favorites, settings, role, dispatcher, RecordingLogger(), handle)

    /** FR-ONBOARD-01: ≤ 4 screens; Welcome → Pick favourites → Set as default → Done. */
    @Test
    fun fourStepsInOrder() {
        assertThat(OnboardingStep.entries).hasSize(4)
        role.defaultHome = true
        val vm = viewModel()
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.WELCOME)
        vm.onAction(OnboardingAction.Continue)
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.FAVORITES)
        vm.onAction(OnboardingAction.Continue)
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.SET_DEFAULT)
        assertThat(vm.state.value.setDefault.held).isTrue()
        vm.onAction(OnboardingAction.Continue)
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.DONE)
        vm.onAction(OnboardingAction.Back)
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.SET_DEFAULT)
    }

    /** T-ONBOARD-02 (logic): nothing preselected, picks stored in order, cap 12, Skip stores nothing. */
    @Test
    fun tOnboard02_pickerRules() {
        val vm = viewModel()
        vm.onAction(OnboardingAction.Continue)
        assertThat(vm.state.value.picked).isEmpty()
        assertThat(vm.state.value.apps).hasSize(15)
        val three = listOf(apps[4].key, apps[0].key, apps[9].key)
        three.forEach { vm.onAction(OnboardingAction.TogglePick(it)) }
        vm.onAction(OnboardingAction.Continue)
        assertThat(favorites.state.value).containsExactlyElementsIn(three).inOrder()
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.SET_DEFAULT)
    }

    @Test
    fun tOnboard02_limitTwelve() {
        val vm = viewModel()
        vm.onAction(OnboardingAction.Continue)
        apps.take(13).forEach { vm.onAction(OnboardingAction.TogglePick(it.key)) }
        assertThat(vm.state.value.picked).hasSize(12)
        assertThat(vm.state.value.limitReached).isTrue()
        vm.onAction(OnboardingAction.TogglePick(apps[0].key)) // unpick one: room again
        assertThat(vm.state.value.limitReached).isFalse()
        vm.onAction(OnboardingAction.TogglePick(apps[12].key))
        assertThat(vm.state.value.picked).hasSize(12)
        vm.onAction(OnboardingAction.Continue)
        assertThat(favorites.state.value).hasSize(12)
    }

    @Test
    fun tOnboard02_skipStoresNothing() {
        val vm = viewModel()
        vm.onAction(OnboardingAction.Continue)
        vm.onAction(OnboardingAction.TogglePick(apps[1].key))
        vm.onAction(OnboardingAction.SkipFavorites)
        assertThat(favorites.state.value).isEmpty()
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.SET_DEFAULT)
    }

    @Test
    fun searchFiltersByLabel() {
        val vm = viewModel()
        vm.onAction(OnboardingAction.Continue)
        vm.onAction(OnboardingAction.Query("app 1"))
        assertThat(
            vm.state.value.apps!!
                .map { it.displayLabel },
        ).containsExactly("App 1", "App 10", "App 11", "App 12", "App 13", "App 14")
    }

    /** T-ONBOARD-06 (flow half): "Not now" ends the flow with `onboarding_done`; home shows the banner. */
    @Test
    fun tOnboard06_notNowFinishes() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.effects.test {
                vm.onAction(OnboardingAction.Continue)
                vm.onAction(OnboardingAction.SkipFavorites)
                assertThat(vm.state.value.setDefault.held).isFalse()
                vm.onAction(OnboardingAction.NotNow)
                assertThat(awaitItem()).isEqualTo(OnboardingEffect.Finished)
                assertThat(settings.value.onboardingDone).isTrue()
            }
        }

    /** J-01 step 4: RESULT_OK and held → Done, without another tap. */
    @Test
    fun grantedAttemptAdvancesToDone() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.onAction(OnboardingAction.Continue)
            vm.onAction(OnboardingAction.SkipFavorites)
            vm.setDefault.requests.test {
                vm.onAction(OnboardingAction.SetAsDefault)
                awaitItem()
                role.defaultHome = true
                vm.setDefault.onReturned(SetDefaultAttempt.ROLE, ok = true, openMillis = 3_000)
                assertThat(vm.state.value.step).isEqualTo(OnboardingStep.DONE)
            }
        }

    /** Step and picks survive process death (SavedStateHandle). */
    @Test
    fun restoresStepAndPicks() {
        val handle = SavedStateHandle()
        val first = viewModel(handle)
        first.onAction(OnboardingAction.Continue)
        first.onAction(OnboardingAction.TogglePick(apps[2].key))
        val second = viewModel(SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        assertThat(second.state.value.step).isEqualTo(OnboardingStep.FAVORITES)
        assertThat(second.state.value.picked).containsExactly(apps[2].key)
    }
}

/** [count] shown apps "App 0"… in one section. */
internal fun onboardingApps(count: Int): AppSection =
    AppSection(
        "A",
        (0 until count).map { i ->
            VisibleApp(
                key = AppKey("pkg$i/pkg$i.Main", 0),
                packageName = "pkg$i",
                displayLabel = "App $i",
                systemLabel = "App $i",
                sectionKey = "A",
                profileKind = ProfileKind.MAIN,
                hidden = false,
            )
        },
    )

private fun FakeAppListSource(sections: List<AppSection>) =
    FakeAppListSource(AppList(sections, emptyList(), hiddenCount = 0, fromSnapshot = false))
