package app.strata.launcher.feature.widgets

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.WidgetHeights
import app.strata.launcher.core.model.WidgetPlacement
import app.strata.launcher.core.model.WidgetPlacementState
import app.strata.launcher.feature.widgets.WidgetFixtures.clock
import app.strata.launcher.testing.fakes.FakeWidgetHost
import app.strata.launcher.testing.fakes.FakeWidgetRepository
import app.strata.launcher.testing.fakes.RecordingLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The header widget area (level R, fakes): T-WIDGET-06, -07, -09 view-model halves, orphans, safe mode. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class WidgetAreaViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher)
    private val host = FakeWidgetHost(WidgetFixtures.all)
    private val repo = FakeWidgetRepository()
    private val safeMode = MutableStateFlow(false)
    private val effects = mutableListOf<WidgetAreaEffect>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun placed(replaceClock: Boolean = true): Int {
        val id = host.bindDirect(clock.ref)
        repo.state.value = WidgetPlacement(id, clock.ref, replaceClock = replaceClock)
        return id
    }

    private fun vm() =
        WidgetAreaViewModel(host, repo, RecordingLogger(), safeMode).also { vm ->
            scope.launch { vm.effects.collect { effects += it } }
        }

    @Test
    fun tWidget01_shownAndReplacesClock() {
        placed()
        val vm = vm()
        vm.refresh()
        assertThat(vm.state.value.shown).isTrue()
        assertThat(vm.state.value.replacesClock).isTrue()
        assertThat(vm.state.value.minHeightDp).isEqualTo(clock.minHeightDp)
    }

    @Test
    fun tWidget06_resizeStepsClamped() {
        placed()
        val vm = vm()
        vm.onAction(WidgetAreaAction.Shorter)
        assertThat(repo.state.value!!.heightStep).isEqualTo(1)
        repeat(2) { vm.onAction(WidgetAreaAction.Taller) }
        assertThat(repo.state.value!!.heightStep).isEqualTo(3)
        repeat(3) { vm.onAction(WidgetAreaAction.Taller) }
        assertThat(repo.state.value!!.heightStep).isEqualTo(4)
    }

    @Test
    fun tWidget07_removeDeletesRowAndId() {
        val id = placed()
        val vm = vm()
        vm.onAction(WidgetAreaAction.OpenMenu)
        assertThat(vm.state.value.menuOpen).isTrue()
        vm.onAction(WidgetAreaAction.Remove)
        assertThat(repo.state.value).isNull()
        assertThat(host.deleted).contains(id)
        assertThat(runBlocking { host.hostIds() }).isEmpty()
        assertThat(vm.state.value.shown).isFalse()
        assertThat(vm.state.value.menuOpen).isFalse()
    }

    @Test
    fun tWidget09_providerLost_placeholder_reAddOpensBindForSameProvider() {
        placed()
        val vm = vm()
        host.loseProvider(clock.ref) // emits PROVIDERS_CHANGED → refresh
        assertThat(repo.state.value!!.state).isEqualTo(WidgetPlacementState.UNAVAILABLE)
        assertThat(vm.state.value.shown).isTrue()
        // A placeholder never hides the clock.
        assertThat(vm.state.value.replacesClock).isFalse()
        vm.onAction(WidgetAreaAction.ReAdd)
        assertThat(effects).containsExactly(WidgetAreaEffect.OpenPicker(clock.ref))
    }

    @Test
    fun orphanIdsAreFreed_butNotWhileAPlacementWaitsForItsRemap() {
        val id = placed()
        val orphan = host.bindDirect(WidgetFixtures.alarm.ref)
        vm().refresh()
        assertThat(host.deleted).containsExactly(orphan)
        assertThat(host.boundIds).containsExactly(id)

        // A restored placement whose ID is not bound (yet): the host's new IDs are kept for the remap.
        repo.state.value = WidgetPlacement(99, clock.ref)
        val restored = host.bindDirect(clock.ref)
        vm().refresh()
        assertThat(host.deleted).doesNotContain(restored)
        assertThat(repo.state.value!!.state).isEqualTo(WidgetPlacementState.UNAVAILABLE)
    }

    /** Review of PR #25 item 7: an ID a bind flow holds (allocated, not placed) is not an orphan. */
    @Test
    fun idsInFlightAreNotSwept() {
        val id = placed()
        val step = runBlocking { host.allocateAndBind(WidgetFixtures.alarm.ref) }
        val inFlight = (step as app.strata.launcher.core.platformapi.widgets.BindStep.Bound).id
        vm().refresh()
        assertThat(host.deleted).isEmpty()
        assertThat(host.boundIds).containsExactly(id, inFlight)
        host.settled(inFlight)
        vm().refresh()
        assertThat(host.deleted).containsExactly(inFlight)
    }

    /** Review item 8: the view is created only after the check confirmed the ID (provider info published off main). */
    @Test
    fun hostReadyOnlyAfterTheCheck_andAgainAfterARemap() {
        val id = placed()
        val vm = vm()
        assertThat(vm.state.value.hostReady).isTrue() // the placement flow triggered the check
        val other = host.bindDirect(clock.ref)
        runBlocking { repo.remap(mapOf(id to other)) }
        assertThat(
            vm.state.value.placement!!
                .appWidgetId,
        ).isEqualTo(other)
        assertThat(vm.state.value.hostReady).isTrue()
        runBlocking { repo.remap(mapOf(other to 0)) }
        assertThat(vm.state.value.hostReady).isFalse()
    }

    @Test
    fun safeMode_widgetNotShown_clockStays() {
        placed()
        safeMode.value = true
        val vm = vm()
        assertThat(vm.state.value.shown).isFalse()
        assertThat(vm.state.value.replacesClock).isFalse()
        safeMode.value = false
        assertThat(vm.state.value.shown).isTrue()
    }

    @Test
    fun toggleClockAndReplace() {
        placed(replaceClock = false)
        val vm = vm()
        vm.onAction(WidgetAreaAction.ToggleClock)
        assertThat(repo.state.value!!.replaceClock).isTrue()
        vm.onAction(WidgetAreaAction.Replace)
        assertThat(effects).containsExactly(WidgetAreaEffect.OpenPicker(null))
    }

    /**
     * CI run 43220546655 (API 26 tWidget06): taps that arrive before the previous write has flowed back to the state
     * must each count. The published flow here lags (it never re-emits after the first value); steps come from storage.
     */
    @Test
    fun quickResizeTapsEachCount_evenWhenThePublishedStateLags() {
        val id = placed()
        val lagging =
            object : app.strata.launcher.core.platformapi.widgets.WidgetRepository by repo {
                override val placement = MutableStateFlow(repo.state.value)
            }
        val vm = WidgetAreaViewModel(host, lagging, RecordingLogger(), safeMode)
        vm.refresh()
        vm.onAction(WidgetAreaAction.StartResize)
        vm.onAction(WidgetAreaAction.Taller)
        vm.onAction(WidgetAreaAction.Taller)
        assertThat(repo.state.value?.heightStep).isEqualTo(3)
        repeat(5) { vm.onAction(WidgetAreaAction.Taller) }
        assertThat(repo.state.value?.heightStep).isEqualTo(WidgetHeights.MAX_STEP)
        repeat(9) { vm.onAction(WidgetAreaAction.Shorter) }
        assertThat(repo.state.value?.heightStep).isEqualTo(WidgetHeights.MIN_STEP)
        assertThat(repo.state.value?.appWidgetId).isEqualTo(id)
    }
}
