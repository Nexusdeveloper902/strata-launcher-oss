package app.strata.launcher.testing.fakes

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.model.BadgeKey
import app.strata.launcher.core.platformapi.apps.LaunchError
import app.strata.launcher.core.platformapi.apps.LaunchOrigin
import app.strata.launcher.core.platformapi.apps.LaunchResult
import app.strata.launcher.core.platformapi.notifications.BadgeSource
import app.strata.launcher.core.platformapi.notifications.ListenerAccess
import app.strata.launcher.core.platformapi.system.CallMode
import app.strata.launcher.core.platformapi.system.ContactHit
import app.strata.launcher.core.platformapi.system.ContactsSource
import app.strata.launcher.core.platformapi.system.PermissionState
import app.strata.launcher.core.platformapi.system.PhoneNumber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory [BadgeSource] (T-NOTIF-04). [systemAccess] is what the system settings say; [counts] are the raw
 * alerting counts of a connected listener. [badges] follows the real source: empty unless active and granted.
 */
class FakeBadgeSource(
    systemAccess: ListenerAccess = ListenerAccess.NOT_GRANTED,
) : BadgeSource {
    /** What the next [refresh] reads (the user's choice on the grant page). */
    var systemAccess: ListenerAccess = systemAccess

    val counts = MutableStateFlow<Map<BadgeKey, Int>>(emptyMap())
    private val accessState = MutableStateFlow(systemAccess)
    private val badgeState = MutableStateFlow<Map<BadgeKey, Int>>(emptyMap())
    var active: Boolean? = null
        private set
    var grantScreensOpened = 0
        private set
    var grantScreenMissing = false

    override val access: StateFlow<ListenerAccess> = accessState
    override val badges: StateFlow<Map<BadgeKey, Int>> = badgeState

    override fun setActive(active: Boolean) {
        this.active = active
        update()
    }

    /** When set, [refresh] reads the access, then waits for it: a check that is still running (race tests). */
    var refreshGate: CompletableDeferred<Unit>? = null

    override suspend fun refresh(): ListenerAccess {
        val read = systemAccess
        refreshGate?.await()
        accessState.value = read
        update()
        return read
    }

    override fun openGrantScreen(): LaunchResult {
        if (grantScreenMissing) return Outcome.Err(LaunchError.NOT_FOUND)
        grantScreensOpened++
        return Outcome.Ok(Unit)
    }

    /** Posts or replaces the counts (as a connected listener would) and republishes. */
    fun post(counts: Map<BadgeKey, Int>) {
        this.counts.value = counts
        update()
    }

    private fun update() {
        badgeState.value = if (active == true && accessState.value.granted) counts.value else emptyMap()
    }
}

/**
 * In-memory [ContactsSource] (T-SEARCH-12…14, T-SEARCH-23…25): [contacts] are matched by a case-insensitive name prefix
 * of any word; [numbers] are keyed by lookup URI. [calls] records each started hand-off with the mode it was started
 * in, which follows the real source: [CallMode.CALL] without [callPermissionState] granted dials instead.
 */
class FakeContactsSource(
    var permissionState: PermissionState = PermissionState.DENIED,
    var contacts: List<ContactHit> = emptyList(),
    var callPermissionState: PermissionState = PermissionState.DENIED,
    var numbers: Map<String, List<PhoneNumber>> = emptyMap(),
) : ContactsSource {
    val queries = mutableListOf<String>()
    val opened = mutableListOf<ContactHit>()
    var openFails = false

    /** Lookup URIs whose numbers were read. */
    val numberQueries = mutableListOf<String>()

    /** (number, mode as started) of every call hand-off. */
    val calls = mutableListOf<Pair<String, CallMode>>()

    /** No dialer: every [call] gives `NOT_FOUND`. */
    var callFails = false

    /** When set, [numbers] waits for it (a slow provider; cancellation tests). */
    var numbersGate: CompletableDeferred<Unit>? = null

    override fun permission(): PermissionState = permissionState

    override suspend fun search(
        query: String,
        limit: Int,
    ): List<ContactHit> {
        if (permissionState != PermissionState.GRANTED || query.isBlank()) return emptyList()
        queries += query
        val q = query.trim().lowercase()
        return contacts
            .filter { c ->
                c.name
                    .lowercase()
                    .split(' ')
                    .any { it.startsWith(q) } || c.name.lowercase().startsWith(q)
            }.take(limit)
    }

    override fun open(
        hit: ContactHit,
        origin: LaunchOrigin?,
    ): LaunchResult {
        if (openFails) return Outcome.Err(LaunchError.NOT_FOUND)
        opened += hit
        return Outcome.Ok(Unit)
    }

    override fun callPermission(): PermissionState = callPermissionState

    override suspend fun numbers(hit: ContactHit): List<PhoneNumber> {
        if (permissionState != PermissionState.GRANTED) return emptyList()
        numberQueries += hit.lookupUri
        numbersGate?.await()
        return numbers[hit.lookupUri].orEmpty().take(ContactsSource.MAX_NUMBERS)
    }

    override fun call(
        number: String,
        mode: CallMode,
    ): LaunchResult {
        if (!ContactsSource.isDialable(number)) return Outcome.Err(LaunchError.SECURITY)
        if (callFails) return Outcome.Err(LaunchError.NOT_FOUND)
        val started =
            if (mode == CallMode.CALL &&
                ContactsSource.isPlainNumber(number) &&
                callPermissionState == PermissionState.GRANTED
            ) {
                mode
            } else {
                CallMode.DIAL
            }
        calls += number to started
        return Outcome.Ok(Unit)
    }
}
