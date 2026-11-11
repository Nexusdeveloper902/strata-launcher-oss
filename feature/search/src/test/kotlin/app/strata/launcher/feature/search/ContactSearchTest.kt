package app.strata.launcher.feature.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.platformapi.system.ContactHit
import app.strata.launcher.core.platformapi.system.PermissionState
import app.strata.launcher.testing.fakes.FakeContactsSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** FR-SEARCH-06 in the search view model (level R): T-SEARCH-12/13/14 halves and the one-time hint (INTERACTION_SPEC §8). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ContactSearchTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val people =
        listOf(
            "Ann Lee",
            "Anna Berg",
            "Annika Roth",
            "Joanne Ann",
            "Annette Fox",
            "Anne Marie",
            "Annabel Cruz",
            "Bob Ray",
        ).mapIndexed { i, n -> ContactHit("content://com.android.contacts/contacts/lookup/k$i/$i", n) }

    private fun harness(
        enabled: Boolean,
        permission: PermissionState,
        hintShown: Boolean = false,
    ) = SearchHarness(
        dispatcher = dispatcher,
        settings = Settings(onboardingDone = true, contactsSearchEnabled = enabled, contactsHintShown = hintShown),
        contacts = FakeContactsSource(permission, people),
    )

    /** T-SEARCH-12 (R): contacts off → no query ever reaches the provider, no contact rows. */
    @Test
    fun tSearch12_offByDefault_noContactQueries() {
        val h = harness(enabled = false, permission = PermissionState.GRANTED, hintShown = true)
        h.viewModel.onAction(SearchAction.Open())
        repeat(20) { i -> h.viewModel.onAction(SearchAction.QueryChanged("an" + "n".repeat(i % 3))) }
        assertThat(h.contacts!!.queries).isEmpty()
        assertThat(h.viewModel.state.value.contacts).isEmpty()
    }

    /** T-SEARCH-13 (R): on and granted → at most 5 contacts for "ann", after the apps; a tap opens it and closes. */
    @Test
    fun tSearch13_atMostFiveBelowApps() {
        val h = harness(enabled = true, permission = PermissionState.GRANTED)
        h.viewModel.onAction(SearchAction.Open("ann"))
        val s = h.viewModel.state.value
        assertThat(s.contacts).hasSize(5)
        assertThat(s.contacts.first().name).isEqualTo("Ann Lee")
        // Keyboard selection reaches the contacts after the apps (FR-SEARCH-04).
        assertThat(s.selectableCount).isEqualTo(s.items.size + 5)
        h.viewModel.onAction(SearchAction.OpenContact(s.contacts.first(), null))
        assertThat(
            h.contacts!!
                .opened
                .single()
                .name,
        ).isEqualTo("Ann Lee")
        assertThat(h.viewModel.state.value.query).isEmpty()
    }

    /** Enter with no app match opens the first contact. */
    @Test
    fun enterOpensFirstContactWhenNoAppMatches() {
        val h = harness(enabled = true, permission = PermissionState.GRANTED)
        h.viewModel.onAction(SearchAction.Open("bob"))
        assertThat(h.viewModel.state.value.results).isEmpty()
        h.viewModel.onAction(SearchAction.LaunchSelected)
        assertThat(
            h.contacts!!
                .opened
                .single()
                .name,
        ).isEqualTo("Bob Ray")
    }

    /** T-SEARCH-14 (R, search half): enabled but not granted → no rows; app search works as before. */
    @Test
    fun tSearch14_deniedSearchWorksAsBefore() {
        val h = harness(enabled = true, permission = PermissionState.DENIED)
        h.viewModel.onAction(SearchAction.Open("ca"))
        assertThat(h.viewModel.state.value.contacts).isEmpty()
        assertThat(h.viewModel.state.value.results).isNotEmpty()
        assertThat(h.contacts!!.queries).isEmpty()
    }

    /** A revoke while search is open takes effect on the next resume. */
    @Test
    fun revokeOnResumeDropsContacts() {
        val h = harness(enabled = true, permission = PermissionState.GRANTED)
        h.viewModel.onAction(SearchAction.Open("ann"))
        assertThat(h.viewModel.state.value.contacts).isNotEmpty()
        h.contacts!!.permissionState = PermissionState.DENIED
        h.viewModel.onAction(SearchAction.Resume)
        assertThat(h.viewModel.state.value.contacts).isEmpty()
    }

    /** INTERACTION_SPEC §8: the hint shows once (contacts off, no app match, a name-like query) and is remembered. */
    @Test
    fun contactsHintShownOnce() {
        val h = harness(enabled = false, permission = PermissionState.DENIED)
        h.viewModel.onAction(SearchAction.Open("zzz9"))
        assertThat(h.viewModel.state.value.showContactsHint).isFalse() // digits: not a name
        h.viewModel.onAction(SearchAction.QueryChanged("joanna"))
        assertThat(h.viewModel.state.value.showContactsHint).isTrue()
        assertThat(h.settings.value.contactsHintShown).isTrue()
        h.viewModel.onAction(SearchAction.QueryChanged("joannas"))
        assertThat(h.viewModel.state.value.showContactsHint).isTrue() // stays for this session
        h.viewModel.onAction(SearchAction.Close)
        h.viewModel.onAction(SearchAction.Open("joanna"))
        assertThat(h.viewModel.state.value.showContactsHint).isFalse()
    }

    /** The hint row closes search and asks home to open Settings. */
    @Test
    fun hintOpensSettings() =
        kotlinx.coroutines.test.runTest(dispatcher) {
            val h = harness(enabled = false, permission = PermissionState.DENIED)
            val effects = mutableListOf<SearchEffect>()
            val job = backgroundScope.launch { h.viewModel.effects.collect { effects += it } }
            h.viewModel.onAction(SearchAction.Open("joanna"))
            h.viewModel.onAction(SearchAction.ContactsHint)
            assertThat(effects).containsExactly(SearchEffect.OpenSettings, SearchEffect.Close).inOrder()
            job.cancel()
        }

    @Test
    fun nameLikeQuery() {
        assertThat(NameLikeQuery.matches("Ann")).isTrue()
        assertThat(NameLikeQuery.matches("O'Neil")).isTrue()
        assertThat(NameLikeQuery.matches("a")).isFalse()
        assertThat(NameLikeQuery.matches("r2d2")).isFalse()
    }
}
