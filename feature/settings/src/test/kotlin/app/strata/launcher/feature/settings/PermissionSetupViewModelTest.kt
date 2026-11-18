package app.strata.launcher.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.SwipeToCall
import app.strata.launcher.core.platformapi.notifications.ListenerAccess
import app.strata.launcher.core.platformapi.system.PermissionState
import app.strata.launcher.testing.fakes.FakeAppListSource
import app.strata.launcher.testing.fakes.FakeBadgeSource
import app.strata.launcher.testing.fakes.FakeContactsSource
import app.strata.launcher.testing.fakes.FakeLaunchLogRepository
import app.strata.launcher.testing.fakes.FakeLockService
import app.strata.launcher.testing.fakes.FakeOverridesRepository
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fakes.FakeSystemIntents
import app.strata.launcher.testing.fakes.RecordingLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.CoroutineContext

/**
 * The settings half of FR-SEARCH-09 (T-SET-11: the swipe-to-call choice and its `CALL_PHONE` request) and the FR-SET-08
 * checklist state (T-SET-12 rows and counts, T-SET-13 restricted-settings guide), at the view-model level (R).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PermissionSetupViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val settings = FakeSettingsRepository(Settings(onboardingDone = true))
    private val intents = FakeSystemIntents()

    /** Runs blocks at once on the calling thread and counts them: shows that a call went through [ioDispatcher]. */
    private class CountingDispatcher : CoroutineDispatcher() {
        var dispatched = 0

        override fun isDispatchNeeded(context: CoroutineContext) = true

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            dispatched++
            block.run()
        }
    }

    private fun vm(
        contacts: FakeContactsSource = FakeContactsSource(),
        badges: FakeBadgeSource = FakeBadgeSource(),
        lock: FakeLockService = FakeLockService(),
        role: FakeRoleController = FakeRoleController(),
        restricted: Boolean = false,
        fromStore: (() -> Boolean)? = null,
        io: CoroutineDispatcher = dispatcher,
        handle: SavedStateHandle = SavedStateHandle(),
    ) = SettingsViewModel(
        settings = settings,
        launchLog = FakeLaunchLogRepository(),
        appList = FakeAppListSource(),
        overrides = FakeOverridesRepository(),
        lockService = lock,
        systemIntents = intents,
        logger = RecordingLogger(),
        roleController = role,
        ioDispatcher = io,
        badges = badges,
        contacts = contacts,
        restrictedSettingsHint = restricted,
        installedFromStore = fromStore,
        handle = handle,
    )

    private fun TestScope.effectsOf(vm: SettingsViewModel): MutableList<SettingsEffect> {
        val out = mutableListOf<SettingsEffect>()
        backgroundScope.launch { vm.effects.collect { out += it } }
        return out
    }

    /** T-SET-11: opening settings asks for nothing; "Call directly" asks for CALL_PHONE; a grant then writes CALL. */
    @Test
    fun tSet11_callDirectlyRequestsThenGrants() =
        runTest(dispatcher) {
            val contacts = FakeContactsSource(callPermissionState = PermissionState.DENIED)
            val vm = vm(contacts)
            val effects = effectsOf(vm)
            vm.onAction(SettingsAction.Resume)
            assertThat(effects).doesNotContain(SettingsEffect.RequestCallPhone)
            assertThat(vm.state.value.swipeToCall).isEqualTo(SwipeToCall.DIAL)
            vm.onAction(SettingsAction.SetSwipeToCall(SwipeToCall.CALL))
            assertThat(effects).containsExactly(SettingsEffect.RequestCallPhone)
            assertThat(settings.value.swipeToCall).isEqualTo(SwipeToCall.DIAL) // not before the grant
            contacts.callPermissionState = PermissionState.GRANTED
            vm.onAction(SettingsAction.CallPermissionResult(granted = true))
            assertThat(settings.value.swipeToCall).isEqualTo(SwipeToCall.CALL)
            assertThat(vm.state.value.swipeToCall).isEqualTo(SwipeToCall.CALL)
            assertThat(vm.state.value.callPermissionNeeded).isFalse()
        }

    /** T-SET-11: a denial keeps the setting and shows "Permission needed"; the dialog's answer is checked. */
    @Test
    fun tSet11_denialKeepsDialWithNote() {
        val contacts = FakeContactsSource(callPermissionState = PermissionState.DENIED)
        val vm = vm(contacts)
        vm.onAction(SettingsAction.SetSwipeToCall(SwipeToCall.CALL))
        // "granted" from a dialog the platform does not confirm counts as denied.
        vm.onAction(SettingsAction.CallPermissionResult(granted = true))
        assertThat(settings.value.swipeToCall).isEqualTo(SwipeToCall.DIAL)
        assertThat(vm.state.value.callPermissionNeeded).isTrue()
        // Picking the dialer clears the note without any request.
        vm.onAction(SettingsAction.SetSwipeToCall(SwipeToCall.DIAL))
        assertThat(vm.state.value.callPermissionNeeded).isFalse()
    }

    /** T-SET-11: granted, then revoked in the system settings: on resume the choice reads "Open the dialer" + note. */
    @Test
    fun tSet11_revokeShowsDialAndNote() {
        settings.set(settings.value.copy(swipeToCall = SwipeToCall.CALL))
        val contacts = FakeContactsSource(callPermissionState = PermissionState.GRANTED)
        val vm = vm(contacts)
        assertThat(vm.state.value.swipeToCall).isEqualTo(SwipeToCall.CALL)
        contacts.callPermissionState = PermissionState.DENIED
        vm.onAction(SettingsAction.Resume)
        assertThat(vm.state.value.swipeToCall).isEqualTo(SwipeToCall.DIAL)
        assertThat(vm.state.value.callPermissionNeeded).isTrue()
        assertThat(settings.value.swipeToCall).isEqualTo(SwipeToCall.CALL) // the choice is kept for a later grant
    }

    /** T-SET-11: Off and Dial are written at once without a request; a result nobody asked for is ignored. */
    @Test
    fun tSet11_offAndDialNeedNoRequest() =
        runTest(dispatcher) {
            val contacts = FakeContactsSource(callPermissionState = PermissionState.DENIED)
            val vm = vm(contacts)
            val effects = effectsOf(vm)
            vm.onAction(SettingsAction.SetSwipeToCall(SwipeToCall.OFF))
            assertThat(settings.value.swipeToCall).isEqualTo(SwipeToCall.OFF)
            vm.onAction(SettingsAction.SetSwipeToCall(SwipeToCall.DIAL))
            assertThat(settings.value.swipeToCall).isEqualTo(SwipeToCall.DIAL)
            contacts.callPermissionState = PermissionState.GRANTED
            vm.onAction(SettingsAction.CallPermissionResult(granted = true))
            assertThat(settings.value.swipeToCall).isEqualTo(SwipeToCall.DIAL)
            assertThat(effects).isEmpty()
        }

    /** T-SET-11: already granted → CALL is written at once; the CALL_PHONE request in flight survives process death. */
    @Test
    fun tSet11_grantedWritesAtOnceAndRequestSurvivesProcessDeath() {
        val granted = FakeContactsSource(callPermissionState = PermissionState.GRANTED)
        vm(granted).onAction(SettingsAction.SetSwipeToCall(SwipeToCall.CALL))
        assertThat(settings.value.swipeToCall).isEqualTo(SwipeToCall.CALL)
        settings.set(settings.value.copy(swipeToCall = SwipeToCall.DIAL))
        val handle = SavedStateHandle()
        vm(FakeContactsSource(), handle = handle).onAction(SettingsAction.SetSwipeToCall(SwipeToCall.CALL))
        val restored =
            vm(
                FakeContactsSource(callPermissionState = PermissionState.GRANTED),
                handle = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }),
            )
        restored.onAction(SettingsAction.CallPermissionResult(granted = true))
        assertThat(settings.value.swipeToCall).isEqualTo(SwipeToCall.CALL)
    }

    /** T-SET-12: every offered access is a row, in checklist order, with the count of those given. */
    @Test
    fun tSet12_rowsAndCount() {
        val vm =
            vm(
                contacts = FakeContactsSource(PermissionState.GRANTED, callPermissionState = PermissionState.DENIED),
                badges = FakeBadgeSource(ListenerAccess.NOT_GRANTED),
                lock = FakeLockService(supported = true, enabled = false),
                role = FakeRoleController(defaultHome = true),
            )
        vm.onAction(SettingsAction.Resume)
        val items = vm.state.value.accessItems
        assertThat(items.map { it.kind })
            .containsExactly(
                AccessKind.DEFAULT_HOME,
                AccessKind.NOTIFICATIONS,
                AccessKind.CONTACTS,
                AccessKind.PHONE,
                AccessKind.SCREEN_LOCK,
            ).inOrder()
        assertThat(items.filter { it.granted }.map { it.kind })
            .containsExactly(AccessKind.DEFAULT_HOME, AccessKind.CONTACTS)
            .inOrder()
        // No lock below API 28, no notification row where listeners are unsupported.
        val bare =
            vm(
                badges = FakeBadgeSource(ListenerAccess.UNSUPPORTED),
                lock = FakeLockService(supported = false),
            )
        assertThat(
            bare.state.value.accessItems
                .map { it.kind },
        ).containsExactly(AccessKind.CONTACTS, AccessKind.PHONE)
            .inOrder()
    }

    /** T-SET-12: "Allow" on each row goes through its disclosure first; a given row opens where it is withdrawn. */
    @Test
    fun tSet12_actions() =
        runTest(dispatcher) {
            val badges = FakeBadgeSource(ListenerAccess.NOT_GRANTED)
            val contacts = FakeContactsSource(callPermissionState = PermissionState.DENIED)
            val lock = FakeLockService(supported = true, enabled = false)
            val vm = vm(contacts = contacts, badges = badges, lock = lock)
            val effects = effectsOf(vm)
            vm.onAction(SettingsAction.ManageBadgeAccess)
            assertThat(vm.state.value.optional.showBadgeDisclosure).isTrue()
            assertThat(badges.grantScreensOpened).isEqualTo(0)
            vm.onAction(SettingsAction.AcceptBadgeDisclosure)
            assertThat(badges.grantScreensOpened).isEqualTo(1)
            vm.onAction(SettingsAction.ManageLockAccess)
            assertThat(vm.state.value.showLockDisclosure).isTrue()
            assertThat(intents.started).doesNotContain(FakeSystemIntents.ACCESSIBILITY)
            vm.onAction(SettingsAction.AcceptLockDisclosure)
            assertThat(intents.started).contains(FakeSystemIntents.ACCESSIBILITY)
            vm.onAction(SettingsAction.ManageContactsAccess)
            assertThat(vm.state.value.optional.showContactsDisclosure).isTrue()
            vm.onAction(SettingsAction.ManageCallAccess)
            assertThat(effects).contains(SettingsEffect.RequestCallPhone)
            // Given: the phone row opens App info, where the permission is withdrawn.
            contacts.callPermissionState = PermissionState.GRANTED
            vm.onAction(SettingsAction.ManageCallAccess)
            assertThat(intents.started).contains(FakeSystemIntents.APP_DETAILS)
        }

    /** T-SET-13: the guide shows on Android 13+ for a non-store install while notification access or the lock is off. */
    @Test
    fun tSet13_guideRules() {
        val io = CountingDispatcher()
        val off =
            vm(badges = FakeBadgeSource(ListenerAccess.NOT_GRANTED), restricted = true, fromStore = { false }, io = io)
        assertThat(off.state.value.showRestrictedGuide).isTrue()
        assertThat(io.dispatched).isAtLeast(1) // the install-source read went through the IO dispatcher
        // Installed by a store: nothing is restricted.
        val store = vm(badges = FakeBadgeSource(ListenerAccess.NOT_GRANTED), restricted = true, fromStore = { true })
        assertThat(store.state.value.showRestrictedGuide).isFalse()
        // Below Android 13 (no restricted settings), whatever the source.
        val old = vm(badges = FakeBadgeSource(ListenerAccess.NOT_GRANTED), restricted = false, fromStore = { false })
        assertThat(old.state.value.showRestrictedGuide).isFalse()
        // Both restricted switches already on: no guide.
        val done =
            vm(
                badges = FakeBadgeSource(ListenerAccess.GRANTED),
                lock = FakeLockService(supported = true, enabled = true),
                restricted = true,
                fromStore = { false },
            )
        assertThat(done.state.value.showRestrictedGuide).isFalse()
        // A failing source check shows the guide (it costs a paragraph at most).
        val failing =
            vm(badges = FakeBadgeSource(ListenerAccess.NOT_GRANTED), restricted = true, fromStore = { error("boom") })
        assertThat(failing.state.value.showRestrictedGuide).isTrue()
    }
}
