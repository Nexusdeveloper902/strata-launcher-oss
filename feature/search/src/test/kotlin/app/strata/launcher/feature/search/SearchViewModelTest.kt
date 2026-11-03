package app.strata.launcher.feature.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.platformapi.apps.LaunchError
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
import org.junit.runner.RunWith

/** Robolectric runner: the VM emits `android.os.Trace` sections (T-PERF-05). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SearchViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun labels(h: SearchHarness) =
        h.viewModel.state.value.items
            .map { it.displayLabel }

    @Test
    fun queryRanksAndSettles() {
        val h = SearchHarness(dispatcher = dispatcher)
        h.viewModel.onAction(SearchAction.Open("ca"))
        val s = h.viewModel.state.value
        assertThat(s.settled).isTrue()
        assertThat(labels(h)).containsExactly("Calculator", "Calendar", "Calligraphy", "Camera").inOrder()
        assertThat(s.showStoreRow).isFalse()
    }

    /** T-SEARCH-07 (VM half): hidden apps follow the setting. */
    @Test
    fun tSearch07_hiddenFollowsSetting() {
        val h = SearchHarness(dispatcher = dispatcher)
        h.viewModel.onAction(SearchAction.Open("hid"))
        assertThat(
            h.viewModel.state.value.results
                .single()
                .hidden,
        ).isTrue()
        h.settings.set(Settings(onboardingDone = true, showHiddenInSearch = false))
        assertThat(h.viewModel.state.value.results).isEmpty()
    }

    /** T-SEARCH-09 (VM half): Down twice + Enter launches the 3rd item; Go launches the top and resets. */
    @Test
    fun tSearch09_selectionAndEnter() =
        runTest(dispatcher) {
            val h = SearchHarness(dispatcher = dispatcher)
            val vm = h.viewModel
            vm.effects.test {
                vm.onAction(SearchAction.Open("cal"))
                vm.onAction(SearchAction.MoveSelection(1))
                vm.onAction(SearchAction.MoveSelection(1))
                vm.onAction(SearchAction.MoveSelection(1)) // clamps at the 3rd (last) item
                assertThat(vm.state.value.selected).isEqualTo(2)
                vm.onAction(SearchAction.LaunchSelected)
                assertThat(
                    h.launcher.launches
                        .last()
                        .first,
                ).isEqualTo(h.app("Calligraphy").key)
                assertThat(awaitItem()).isEqualTo(SearchEffect.Close)
                assertThat(vm.state.value.query).isEmpty()
                vm.onAction(SearchAction.Open("cal"))
                assertThat(vm.state.value.selected).isEqualTo(0)
                assertThat(labels(h).first()).isEqualTo("Calligraphy") // boosted by the first launch
                vm.onAction(SearchAction.LaunchSelected)
                assertThat(
                    h.launcher.launches
                        .last()
                        .first,
                ).isEqualTo(h.app("Calligraphy").key)
                assertThat(awaitItem()).isEqualTo(SearchEffect.Close)
            }
            assertThat(h.launchLog.events).hasSize(2)
        }

    /** T-SEARCH-11 (VM half): at most 8 recent apps, newest first; hidden ones only with the setting. */
    @Test
    fun tSearch11_recents() =
        runTest(dispatcher) {
            val h = SearchHarness(dispatcher = dispatcher)
            h.launched(
                "Maps",
                "Notes",
                "Weather",
                "Clock",
                "Camera",
                "Calendar",
                "Calculator",
                "Hidden Diary",
                "Maps",
                "Notes",
            )
            h.viewModel.onAction(SearchAction.Open())
            assertThat(labels(h))
                .containsExactly(
                    "Notes",
                    "Maps",
                    "Hidden Diary",
                    "Calculator",
                    "Calendar",
                    "Camera",
                    "Clock",
                    "Weather",
                ).inOrder()
            h.settings.set(Settings(onboardingDone = true, showHiddenInSearch = false))
            assertThat(labels(h)).doesNotContain("Hidden Diary")
        }

    /** T-SEARCH-16 groundwork: disabled or cleared recents show nothing. */
    @Test
    fun recentsDisabledAndCleared() =
        runTest(dispatcher) {
            val h = SearchHarness(dispatcher = dispatcher)
            h.launched("Maps")
            h.viewModel.onAction(SearchAction.Open())
            assertThat(labels(h)).containsExactly("Maps")
            h.launchLog.clear()
            assertThat(labels(h)).isEmpty()
            h.launched("Maps")
            assertThat(labels(h)).containsExactly("Maps")
            h.settings.set(Settings(onboardingDone = true, recentsEnabled = false))
            assertThat(labels(h)).isEmpty()
        }

    /** ARCHITECTURE §8: the usage boost orders ties and is 0 while the launch log is paused. */
    @Test
    fun usageBoostAndPause() =
        runTest(dispatcher) {
            val h = SearchHarness(dispatcher = dispatcher)
            h.launched("Camera", "Camera")
            h.viewModel.onAction(SearchAction.Open("ca"))
            assertThat(labels(h).first()).isEqualTo("Camera")
            h.settings.set(Settings(onboardingDone = true, launchLogPaused = true))
            assertThat(labels(h).first()).isEqualTo("Calculator")
        }

    /** T-SEARCH-15 (VM half): store row only with no results and a handler; Enter hands off too. */
    @Test
    fun tSearch15_storeRow() {
        val h = SearchHarness(dispatcher = dispatcher)
        h.viewModel.onAction(SearchAction.Open("zzq"))
        assertThat(h.viewModel.state.value.noResults).isTrue()
        assertThat(h.viewModel.state.value.showStoreRow).isTrue()
        h.viewModel.onAction(SearchAction.LaunchSelected)
        assertThat(h.intents.storeSearches).containsExactly("zzq")

        val none = SearchHarness(storeHandler = false, dispatcher = dispatcher)
        none.viewModel.onAction(SearchAction.Open("zzq"))
        assertThat(none.viewModel.state.value.noResults).isTrue()
        assertThat(none.viewModel.state.value.showStoreRow).isFalse()
    }

    /** INTERACTION_SPEC §8: a failed launch reports and reconciles; search stays open. */
    @Test
    fun launchFailure() =
        runTest(dispatcher) {
            val h = SearchHarness(dispatcher = dispatcher)
            h.launcher.failing += h.app("Maps").key
            h.viewModel.effects.test {
                h.viewModel.onAction(SearchAction.Open("maps"))
                h.viewModel.onAction(SearchAction.LaunchSelected)
                assertThat(
                    awaitItem(),
                ).isEqualTo(SearchEffect.LaunchFailed("Maps", h.app("Maps").key, LaunchError.NOT_FOUND))
            }
            assertThat(h.catalog.reconcileRequests).isEqualTo(1)
            assertThat(h.viewModel.state.value.query).isEqualTo("maps")
        }
}
