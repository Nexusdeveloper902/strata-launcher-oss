package app.strata.launcher.feature.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.SwipeToCall
import app.strata.launcher.core.platformapi.system.CallMode
import app.strata.launcher.core.platformapi.system.ContactHit
import app.strata.launcher.core.platformapi.system.PermissionState
import app.strata.launcher.core.platformapi.system.PhoneNumber
import app.strata.launcher.testing.fakes.FakeContactsSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
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

/** FR-SEARCH-09 in the search view model (level R): T-SEARCH-23 (modes) and T-SEARCH-24 (number choice). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SwipeToCallTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // Invented names and numbers only (555-01xx is reserved for fiction).
    private val zed = hit(0, "Zed Fixture")
    private val zara = hit(1, "Zara Fixture")
    private val zoe = hit(2, "Zoe Fixture")
    private val zane = hit(3, "Zane Fixture")

    private fun hit(
        i: Int,
        name: String,
    ) = ContactHit("content://com.android.contacts/contacts/lookup/fx$i/$i", name)

    private val numbers =
        mapOf(
            zed.lookupUri to listOf(PhoneNumber("+1 555 0100", "Mobile")),
            zara.lookupUri to
                listOf(
                    PhoneNumber("+1 555 0110", "Home"),
                    PhoneNumber("+1 555 0111", "Mobile", primary = true),
                    PhoneNumber("+1 555 0112", "Work"),
                ),
            zoe.lookupUri to listOf(PhoneNumber("+1 555 0120", "Home"), PhoneNumber("+1 555 0121", "Work")),
            zane.lookupUri to emptyList(),
        )

    private fun harness(
        mode: SwipeToCall? = null,
        callPermission: PermissionState = PermissionState.DENIED,
    ): SearchHarness {
        val base = Settings(onboardingDone = true, contactsSearchEnabled = true)
        return SearchHarness(
            dispatcher = dispatcher,
            settings = if (mode == null) base else base.copy(swipeToCall = mode),
            contacts =
                FakeContactsSource(
                    permissionState = PermissionState.GRANTED,
                    contacts = listOf(zed, zara, zoe, zane),
                    callPermissionState = callPermission,
                    numbers = numbers,
                ),
        )
    }

    private fun TestScope.effectsOf(h: SearchHarness): MutableList<SearchEffect> {
        val out = mutableListOf<SearchEffect>()
        backgroundScope.launch { h.viewModel.effects.collect { out += it } }
        return out
    }

    /** T-SEARCH-23: the default (field 25 absent) is DIAL: the dialer opens with the number, and search closes. */
    @Test
    fun tSearch23_defaultDialsAndCloses() =
        runTest(dispatcher) {
            val h = harness()
            val effects = effectsOf(h)
            h.viewModel.onAction(SearchAction.Open("zed"))
            val s = h.viewModel.state.value
            assertThat(s.swipeToCall).isTrue()
            assertThat(s.contacts.map { it.name }).containsExactly("Zed Fixture")
            h.viewModel.onAction(SearchAction.CallContact(zed))
            assertThat(h.contacts!!.calls).containsExactly("+1 555 0100" to CallMode.DIAL)
            assertThat(effects).containsExactly(SearchEffect.Close)
            assertThat(h.viewModel.state.value.query).isEmpty()
        }

    /** T-SEARCH-23: CALL places the call while CALL_PHONE is granted. */
    @Test
    fun tSearch23_callWithPermissionCalls() {
        val h = harness(SwipeToCall.CALL, PermissionState.GRANTED)
        h.viewModel.onAction(SearchAction.Open("zed"))
        h.viewModel.onAction(SearchAction.CallContact(zed))
        assertThat(h.contacts!!.calls).containsExactly("+1 555 0100" to CallMode.CALL)
    }

    /** T-SEARCH-23: CALL without CALL_PHONE (never granted, or revoked since) opens the dialer instead. */
    @Test
    fun tSearch23_callWithoutPermissionDials() {
        val h = harness(SwipeToCall.CALL, PermissionState.DENIED)
        h.viewModel.onAction(SearchAction.Open("zed"))
        h.viewModel.onAction(SearchAction.CallContact(zed))
        assertThat(h.contacts!!.calls).containsExactly("+1 555 0100" to CallMode.DIAL)
        // Granted, then revoked in the system settings: the check is made at the swipe, not cached.
        val g = harness(SwipeToCall.CALL, PermissionState.GRANTED)
        g.viewModel.onAction(SearchAction.Open("zed"))
        g.contacts!!.callPermissionState = PermissionState.DENIED
        g.viewModel.onAction(SearchAction.CallContact(zed))
        assertThat(g.contacts.calls).containsExactly("+1 555 0100" to CallMode.DIAL)
    }

    /** T-SEARCH-23: OFF offers no swipe; a stray action starts nothing and reads no number. */
    @Test
    fun tSearch23_offStartsNothing() {
        val h = harness(SwipeToCall.OFF, PermissionState.GRANTED)
        h.viewModel.onAction(SearchAction.Open("zed"))
        assertThat(h.viewModel.state.value.contacts).isNotEmpty()
        assertThat(h.viewModel.state.value.swipeToCall).isFalse()
        h.viewModel.onAction(SearchAction.CallContact(zed))
        assertThat(h.contacts!!.calls).isEmpty()
        assertThat(h.contacts.numberQueries).isEmpty()
        assertThat(h.viewModel.state.value.query).isEqualTo("zed")
    }

    /** T-SEARCH-23: without a contacts source there is no swipe at all. */
    @Test
    fun tSearch23_noContactsSourceNoSwipe() {
        val h = SearchHarness(dispatcher = dispatcher)
        h.viewModel.onAction(SearchAction.Open("ma"))
        assertThat(h.viewModel.state.value.swipeToCall).isFalse()
    }

    /** T-SEARCH-24: several numbers with a default: the default is called without asking. */
    @Test
    fun tSearch24_defaultNumberWins() {
        val h = harness()
        h.viewModel.onAction(SearchAction.Open("zara"))
        h.viewModel.onAction(SearchAction.CallContact(zara))
        assertThat(h.contacts!!.calls).containsExactly("+1 555 0111" to CallMode.DIAL)
        assertThat(h.viewModel.state.value.numberChoice).isNull()
    }

    /** T-SEARCH-24: several numbers, none default: the chooser lists them; the pick is called; search closes. */
    @Test
    fun tSearch24_chooserThenPick() =
        runTest(dispatcher) {
            val h = harness()
            val effects = effectsOf(h)
            h.viewModel.onAction(SearchAction.Open("zoe"))
            h.viewModel.onAction(SearchAction.CallContact(zoe))
            val choice = h.viewModel.state.value.numberChoice!!
            assertThat(choice.contact).isEqualTo(zoe)
            assertThat(choice.numbers.map { it.number }).containsExactly("+1 555 0120", "+1 555 0121").inOrder()
            assertThat(h.contacts!!.calls).isEmpty()
            // A number that is not in the chooser is ignored (a stale UI event).
            h.viewModel.onAction(SearchAction.ChooseNumber(PhoneNumber("+1 555 0199")))
            assertThat(h.contacts.calls).isEmpty()
            h.viewModel.onAction(SearchAction.ChooseNumber(choice.numbers[1]))
            assertThat(h.contacts.calls).containsExactly("+1 555 0121" to CallMode.DIAL)
            assertThat(h.viewModel.state.value.numberChoice).isNull()
            assertThat(effects).containsExactly(SearchEffect.Close)
        }

    /** T-SEARCH-24: Cancel closes the chooser only; nothing is started and search stays open. */
    @Test
    fun tSearch24_cancelStartsNothing() {
        val h = harness()
        h.viewModel.onAction(SearchAction.Open("zoe"))
        h.viewModel.onAction(SearchAction.CallContact(zoe))
        assertThat(h.viewModel.state.value.numberChoice).isNotNull()
        h.viewModel.onAction(SearchAction.DismissNumberChoice)
        assertThat(h.viewModel.state.value.numberChoice).isNull()
        assertThat(h.contacts!!.calls).isEmpty()
        assertThat(h.viewModel.state.value.query).isEqualTo("zoe")
    }

    /** T-SEARCH-24: no number → the message names the contact; no phone app → its own message. Search stays open. */
    @Test
    fun tSearch24_noNumberAndNoPhoneApp() =
        runTest(dispatcher) {
            val h = harness()
            val effects = effectsOf(h)
            h.viewModel.onAction(SearchAction.Open("zane"))
            h.viewModel.onAction(SearchAction.CallContact(zane))
            assertThat(effects).containsExactly(SearchEffect.NoNumber("Zane Fixture"))
            assertThat(h.contacts!!.calls).isEmpty()
            h.contacts.callFails = true
            h.viewModel.onAction(SearchAction.QueryChanged("zed"))
            h.viewModel.onAction(SearchAction.CallContact(zed))
            assertThat(
                effects,
            ).containsExactly(SearchEffect.NoNumber("Zane Fixture"), SearchEffect.CallFailed).inOrder()
            assertThat(h.viewModel.state.value.query).isEqualTo("zed")
        }

    /** T-SEARCH-24: closing search while the numbers are still being read cancels the call: nothing starts later. */
    @Test
    fun tSearch24_closeCancelsPendingLookup() {
        val h = harness()
        val gate = CompletableDeferred<Unit>()
        h.contacts!!.numbersGate = gate
        h.viewModel.onAction(SearchAction.Open("zed"))
        h.viewModel.onAction(SearchAction.CallContact(zed))
        assertThat(h.contacts.numberQueries).containsExactly(zed.lookupUri)
        h.viewModel.onAction(SearchAction.Close)
        gate.complete(Unit)
        assertThat(h.contacts.calls).isEmpty()
        assertThat(h.viewModel.state.value.numberChoice).isNull()
    }
}
