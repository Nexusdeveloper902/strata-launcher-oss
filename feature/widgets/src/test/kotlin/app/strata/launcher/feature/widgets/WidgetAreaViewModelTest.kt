package app.strata.launcher.feature.widgets

import androidx.test.ext.junit.runners.AndroidJUnit4
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
}
