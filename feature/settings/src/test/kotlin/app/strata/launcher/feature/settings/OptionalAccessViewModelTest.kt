package app.strata.launcher.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.Settings
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The grant and permission requests in flight (review of PR #24): ordering and process death. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class OptionalAccessViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val settings = FakeSettingsRepository(Settings(onboardingDone = true))

    private fun vm(
        badges: FakeBadgeSource = FakeBadgeSource(),
        contacts: FakeContactsSource = FakeContactsSource(),
        handle: SavedStateHandle = SavedStateHandle(),
    ) = SettingsViewModel(
        settings = settings,
        launchLog = FakeLaunchLogRepository(),
        appList = FakeAppListSource(),
        overrides = FakeOverridesRepository(),
        lockService = FakeLockService(),
        systemIntents = FakeSystemIntents(),
        logger = RecordingLogger(),
        roleController = FakeRoleController(),
        ioDispatcher = dispatcher,
        badges = badges,
        contacts = contacts,
        handle = handle,
    )

    /** A check still running when the grant page opens must not consume the user's answer. */
    @Test
    fun staleCheckDoesNotConsumeTheGrant() {
        val badges = FakeBadgeSource()
        val vm = vm(badges)
        val gate = CompletableDeferred<Unit>()
        badges.refreshGate = gate
        vm.onAction(SettingsAction.Resume) // reads NOT_GRANTED, then waits
        badges.refreshGate = null
        vm.onAction(SettingsAction.Badges(true))
        vm.onAction(SettingsAction.AcceptBadgeDisclosure)
        gate.complete(Unit) // the stale NOT_GRANTED arrives while the user is on the grant page
        assertThat(vm.state.value.optional.badgeAttemptFailed).isFalse()
        badges.systemAccess = ListenerAccess.GRANTED
        vm.onAction(SettingsAction.Resume) // the user comes back
        assertThat(settings.value.badgesEnabled).isTrue()
    }

    /** The grant page in flight survives process death: the new instance resolves it on its first resume. */
    @Test
    fun grantResolvedAfterProcessDeath() {
        val handle = SavedStateHandle()
        val first = FakeBadgeSource()
        val vm = vm(first, handle = handle)
        vm.onAction(SettingsAction.Badges(true))
        vm.onAction(SettingsAction.AcceptBadgeDisclosure)
        assertThat(first.grantScreensOpened).isEqualTo(1)
        // Process death: a new view model over the restored handle; the user allowed access meanwhile.
        val restored =
            vm(
                FakeBadgeSource(ListenerAccess.GRANTED),
                handle =
                    SavedStateHandle(
                        handle.keys().associateWith {
                            handle.get<Any>(it)
                        },
                    ),
            )
        restored.onAction(SettingsAction.Resume)
        assertThat(settings.value.badgesEnabled).isTrue()
    }

    /** The `READ_CONTACTS` request in flight survives process death. */
    @Test
    fun contactsResultAfterProcessDeath() {
        val handle = SavedStateHandle()
        vm(handle = handle).apply {
            onAction(SettingsAction.Contacts(true))
            onAction(SettingsAction.AcceptContactsDisclosure)
        }
        val restored =
            vm(
                contacts = FakeContactsSource(PermissionState.GRANTED),
                handle = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }),
            )
        restored.onAction(SettingsAction.ContactsPermissionResult(granted = true))
        assertThat(settings.value.contactsSearchEnabled).isTrue()
    }

    /** A result nobody asked for is ignored. */
    @Test
    fun unrequestedContactsResultIgnored() {
        vm(contacts = FakeContactsSource(PermissionState.GRANTED))
            .onAction(SettingsAction.ContactsPermissionResult(granted = true))
        assertThat(settings.value.contactsSearchEnabled).isFalse()
    }
}
