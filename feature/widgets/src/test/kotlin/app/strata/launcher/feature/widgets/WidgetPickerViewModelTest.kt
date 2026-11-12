package app.strata.launcher.feature.widgets

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.WidgetPlacement
import app.strata.launcher.feature.widgets.WidgetFixtures.clock
import app.strata.launcher.feature.widgets.WidgetFixtures.notes
import app.strata.launcher.testing.fakes.FakeWidgetHost
import app.strata.launcher.testing.fakes.FakeWidgetRepository
import app.strata.launcher.testing.fakes.RecordingLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The bind flow of J-10 (level R, fakes): T-WIDGET-01…05 view-model halves, recreation and process death. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class WidgetPickerViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher)
    private val host = FakeWidgetHost(WidgetFixtures.all)
    private val repo = FakeWidgetRepository()
    private val effects = mutableListOf<WidgetPickerEffect>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun vm(
        handle: SavedStateHandle = SavedStateHandle(),
        readd: app.strata.launcher.core.model.WidgetProviderRef? = null,
    ) = WidgetPickerViewModel(host, repo, handle, RecordingLogger(), scope, readd).also { vm ->
        scope.launch { vm.effects.collect { effects += it } }
    }

    @Test
    fun tWidget02_groupedByAppInLabelOrder_andSearchable() {
        val vm = vm()
        val groups = vm.state.value.groups
        assertThat(groups.map { it.appLabel }).containsExactly("Clock app", "Notes").inOrder()
        assertThat(groups[0].providers.map { it.label }).containsExactly("Alarm", "Clock").inOrder()
        vm.onAction(WidgetPickerAction.Query("ala"))
        assertThat(
            vm.state.value.visibleGroups
                .flatMap { it.providers }
                .map { it.label },
        ).containsExactly("Alarm")
        vm.onAction(WidgetPickerAction.Query("notes"))
        assertThat(
            vm.state.value.visibleGroups
                .map { it.appLabel },
        ).containsExactly("Notes")
    }

    @Test
    fun tWidget01_silentBind_firstAddAsksReplaceClock_thenPlaces() {
        val vm = vm()
        vm.onAction(WidgetPickerAction.Pick(clock.ref))
        assertThat(vm.state.value.askReplaceClock).isTrue()
        vm.onAction(WidgetPickerAction.ReplaceClock(true))
        val placed = repo.state.value!!
        assertThat(placed.provider).isEqualTo(clock.ref)
        assertThat(placed.replaceClock).isTrue()
        assertThat(placed.appWidgetId in host.boundIds).isTrue()
        assertThat(vm.state.value.done).isTrue()
    }

    @Test
    fun tWidget03_consentDenied_idDeleted_notAdded() {
        host.silentBind = false
        val vm = vm()
        vm.onAction(WidgetPickerAction.Pick(clock.ref))
        val consent = effects.filterIsInstance<WidgetPickerEffect.LaunchConsent>().single()
        vm.onConsentResult(false)
        assertThat(effects.last()).isEqualTo(WidgetPickerEffect.NotAdded)
        assertThat(host.deleted).contains(consent.id)
        assertThat(kotlinx.coroutines.runBlocking { host.hostIds() }).isEmpty()
        assertThat(repo.state.value).isNull()
        assertThat(vm.state.value.busy).isFalse()
    }

    @Test
    fun tWidget04_configureCancelled_idDeleted() {
        val vm = vm()
        vm.onAction(WidgetPickerAction.Pick(notes.ref))
        val configure = effects.filterIsInstance<WidgetPickerEffect.LaunchConfigure>().single()
        vm.onConfigureResult(false)
        assertThat(effects.last()).isEqualTo(WidgetPickerEffect.NotAdded)
        assertThat(host.deleted).contains(configure.id)
        assertThat(host.boundIds).isEmpty()
        assertThat(repo.state.value).isNull()
    }

    @Test
    fun tWidget04_configureOk_placesAfterClockChoice() {
        val vm = vm()
        vm.onAction(WidgetPickerAction.Pick(notes.ref))
        vm.onConfigureResult(true)
        vm.onAction(WidgetPickerAction.ReplaceClock(false))
        assertThat(repo.state.value!!.replaceClock).isFalse()
    }

    @Test
    fun replaceClockDialogDismissed_cancelsTheAdd() {
        val vm = vm()
        vm.onAction(WidgetPickerAction.Pick(clock.ref))
        val id = host.boundIds.single()
        vm.onAction(WidgetPickerAction.ReplaceClock(null))
        assertThat(host.deleted).contains(id)
        assertThat(repo.state.value).isNull()
        assertThat(effects.last()).isEqualTo(WidgetPickerEffect.NotAdded)
    }

    /** Recreation (same handle) and process death (a handle rebuilt from its saved contents). */
    @Test
    fun consentAnswerSurvivesRecreationAndProcessDeath() {
        host.silentBind = false
        val handle = SavedStateHandle()
        vm(handle).onAction(WidgetPickerAction.Pick(clock.ref))
        val id = effects.filterIsInstance<WidgetPickerEffect.LaunchConsent>().single().id
        val restored = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })
        val vm2 = vm(restored)
        assertThat(vm2.state.value.busy).isTrue()
        vm2.onConsentResult(true)
        assertThat(vm2.state.value.askReplaceClock).isTrue()
        // The dialog itself comes back after another recreation.
        val vm3 = vm(SavedStateHandle(restored.keys().associateWith { restored.get<Any>(it) }))
        assertThat(vm3.state.value.askReplaceClock).isTrue()
        vm3.onAction(WidgetPickerAction.ReplaceClock(false))
        assertThat(repo.state.value!!.appWidgetId).isEqualTo(id)
    }

    @Test
    fun configureAnswerSurvivesProcessDeath() {
        val handle = SavedStateHandle()
        vm(handle).onAction(WidgetPickerAction.Pick(notes.ref))
        val id = effects.filterIsInstance<WidgetPickerEffect.LaunchConfigure>().single().id
        val vm2 = vm(SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        vm2.onConfigureResult(false)
        assertThat(host.deleted).contains(id)
    }

    /** Review of PR #25 item 5: once placing started, a late abandon() must not delete the placed ID. */
    @Test
    fun abandonAfterPlacingKeepsThePlacedId() {
        val vm = vm()
        vm.onAction(WidgetPickerAction.Pick(clock.ref))
        vm.onAction(WidgetPickerAction.ReplaceClock(true))
        val id = repo.state.value!!.appWidgetId
        vm.abandon()
        assertThat(host.deleted).doesNotContain(id)
        assertThat(host.inFlight).isEmpty() // settled: the orphan sweep may now judge it by the placement
    }

    @Test
    fun abandonFreesTheIdInFlight() {
        val vm = vm()
        vm.onAction(WidgetPickerAction.Pick(notes.ref))
        val id = effects.filterIsInstance<WidgetPickerEffect.LaunchConfigure>().single().id
        vm.abandon()
        assertThat(host.deleted).contains(id)
        // A late answer for the abandoned ID does nothing.
        vm.onConfigureResult(true)
        assertThat(repo.state.value).isNull()
    }

    @Test
    fun replaceKeepsHeightAndClock_andDeletesTheOldId() {
        val oldId = host.bindDirect(clock.ref)
        repo.state.value = WidgetPlacement(oldId, clock.ref, heightStep = 3, replaceClock = true)
        val vm = vm()
        vm.onAction(WidgetPickerAction.Pick(WidgetFixtures.alarm.ref))
        val placed = repo.state.value!!
        assertThat(placed.provider).isEqualTo(WidgetFixtures.alarm.ref)
        assertThat(placed.heightStep).isEqualTo(3)
        assertThat(placed.replaceClock).isTrue()
        assertThat(host.deleted).contains(oldId)
        assertThat(vm.state.value.askReplaceClock).isFalse()
    }

    @Test
    fun tWidget09_readdStartsTheBindForTheSameProvider_once() {
        val handle = SavedStateHandle()
        vm(handle, readd = clock.ref)
        assertThat(host.providerOf.values).containsExactly(clock.ref)
        // Recreation does not start a second bind.
        vm(SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }), readd = clock.ref)
        assertThat(host.providerOf.values).containsExactly(clock.ref)
    }

    @Test
    fun providerGone_notAdded() {
        val vm = vm()
        host.providerList = emptyList()
        vm.onAction(WidgetPickerAction.Pick(clock.ref))
        assertThat(effects.last()).isEqualTo(WidgetPickerEffect.NotAdded)
    }
}
