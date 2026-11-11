package app.strata.launcher.feature.search

import android.os.Trace
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.BadgeStyle
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.apps.AppCatalog
import app.strata.launcher.core.platformapi.apps.AppLauncher
import app.strata.launcher.core.platformapi.apps.AppListSource
import app.strata.launcher.core.platformapi.apps.LaunchError
import app.strata.launcher.core.platformapi.apps.LaunchLogRepository
import app.strata.launcher.core.platformapi.apps.LaunchOrigin
import app.strata.launcher.core.platformapi.settings.SettingsRepository
import app.strata.launcher.core.platformapi.system.ContactHit
import app.strata.launcher.core.platformapi.system.ContactsSource
import app.strata.launcher.core.platformapi.system.PermissionState
import app.strata.launcher.core.platformapi.system.SystemIntents
import app.strata.launcher.core.platformapi.system.TextFolding
import app.strata.launcher.core.search.SearchIndex
import app.strata.launcher.core.search.Transliterate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Trace section around one ranking pass (T-PERF-05 on device). */
const val TRACE_SEARCH_RANK = "strata.searchRank"

/** FR-SEARCH-05: at most this many recent apps for the empty query. */
const val MAX_RECENTS = 8

/** S-SEARCH state (ARCHITECTURE §4 `SearchUiState`). */
data class SearchUiState(
    val query: String = "",
    /** Ranked app results for a non-blank query (FR-SEARCH-02). */
    val results: List<VisibleApp> = emptyList(),
    /** Recent apps for the blank query, newest first (FR-SEARCH-05); empty when disabled. */
    val recents: List<VisibleApp> = emptyList(),
    /** FR-SEARCH-07: "Search apps in store" row (0 app results and a handler resolves). */
    val showStoreRow: Boolean = false,
    /** Keyboard selection in [items] (FR-SEARCH-04); the top item by default. */
    val selected: Int = 0,
    val badgeStyle: BadgeStyle = BadgeStyle.DOT,
    /** False until the results for [query] have been computed (no "no results" flash). */
    val settled: Boolean = true,
    /** FR-SEARCH-06: at most 5 contacts for [query], below the apps; empty unless turned on and granted. */
    val contacts: List<ContactHit> = emptyList(),
    /** INTERACTION_SPEC §8: the one-time "search contacts too?" hint (contacts off, no app match, a name-like query). */
    val showContactsHint: Boolean = false,
) {
    val isBlank: Boolean get() = query.isBlank()

    /** What the result list shows: recents for the blank query, else the ranked results. */
    val items: List<VisibleApp> get() = if (isBlank) recents else results

    /** Keyboard selection covers the apps, then the contacts (FR-SEARCH-04). */
    val selectableCount: Int get() = items.size + if (isBlank) 0 else contacts.size

    /** No app matches the query; contact rows may still follow (FR-SEARCH-06 shows them below the apps). */
    val noResults: Boolean get() = !isBlank && settled && results.isEmpty()
}

/** INTERACTION_SPEC §8 "the query looks like a name": two or more letters, only letters, spaces, `'`, `-` or `.`. */
object NameLikeQuery {
    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.count { it.isLetter() } < 2) return false
        return q.all { it.isLetter() || it == ' ' || it == '\'' || it == '-' || it == '.' || it == '’' }
    }
}

sealed interface SearchAction {
    /** The layer opened (swipe, pill, key or list field) with [seed] as the initial query (FR-SEARCH-01). */
    data class Open(val seed: String = "") : SearchAction

    data class QueryChanged(val query: String) : SearchAction

    /** Up/Down arrow (−1/+1), INTERACTION_SPEC §5. */
    data class MoveSelection(val delta: Int) : SearchAction

    /** Enter / IME Go: launch the selected result (default: top), FR-SEARCH-04. */
    data object LaunchSelected : SearchAction

    data class Launch(val app: VisibleApp, val origin: LaunchOrigin?) : SearchAction

    data object StoreSearch : SearchAction

    data class OpenAppInfo(val key: AppKey) : SearchAction

    /** FR-SEARCH-06: a contact row; opens its lookup URI in the contacts app. */
    data class OpenContact(val contact: ContactHit, val origin: LaunchOrigin?) : SearchAction

    /** The contacts hint row: close search and open Settings, where contact search is turned on. */
    data object ContactsHint : SearchAction

    /** Activity `onResume`: `READ_CONTACTS` may have been granted or revoked in the system settings. */
    data object Resume : SearchAction

    /** Esc / Back / close: back to the origin layer. */
    data object Close : SearchAction
}

sealed interface SearchEffect {
    /** Close the layer and return to the origin (Esc, Back, a launched app). */
    data object Close : SearchEffect

    data class LaunchFailed(val label: String, val key: AppKey, val error: LaunchError) : SearchEffect

    /** FR-APPS-09: a paused work app was chosen; home offers "Turn on work apps?". */
    data class ProfilePaused(val app: VisibleApp) : SearchEffect

    /** No contacts app could open the result. */
    data object ContactOpenFailed : SearchEffect

    /** The contacts hint was tapped: home opens Settings. */
    data object OpenSettings : SearchEffect
}

/**
 * S-SEARCH (FR-SEARCH-01…05, -07; ARCHITECTURE §8). The index is rebuilt off main on every list, override
 * or usage change; each query is ranked with `mapLatest` on [defaultDispatcher] (latest wins).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    appList: AppListSource,
    private val settings: SettingsRepository,
    private val launchLog: LaunchLogRepository,
    private val launcher: AppLauncher,
    private val catalog: AppCatalog,
    private val textFolding: TextFolding,
    private val systemIntents: SystemIntents,
    private val defaultDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
    /** FR-SEARCH-06; null = no contact results and no hint. */
    private val contacts: ContactsSource? = null,
) : ViewModel() {
    private val query = MutableStateFlow("")

    /** `READ_CONTACTS`, read when the layer opens and on resume (never requested here: T-SEARCH-12). */
    private val contactsPermitted = MutableStateFlow(contacts?.permission() == PermissionState.GRANTED)

    /** The hint was shown in this search session: it stays until the layer closes, then never again. */
    private val hintSession = MutableStateFlow(false)
    private val selected = MutableStateFlow(0)
    private val storeAvailable = MutableStateFlow(false)
    private val effectChannel = Channel<SearchEffect>(Channel.BUFFERED)
    private val translitCache = HashMap<String, String>()

    /** The last index included private-space apps (their labels are then in [translitCache]). */
    private var indexedPrivate = false
    private val transliterate = Transliterate { textFolding.transliterate(it) }

    val effects: Flow<SearchEffect> = effectChannel.receiveAsFlow()

    private val currentSettings: StateFlow<Settings?> =
        settings.settings
            .map<Settings, Settings?> { it }
            .catch { emit(Settings.DEFAULT) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val launchChanges: Flow<Unit> = launchLog.changes.onStart { emit(Unit) }

    /** 30-day counts for the usage boost; empty while the log is paused (ARCHITECTURE §8). */
    private val counts: Flow<Map<AppKey, Int>> =
        combine(launchChanges, currentSettings.filterNotNull().map { it.launchLogPaused }.distinctUntilChanged()) {
            _,
            paused,
            ->
            paused
        }.mapLatest { paused ->
            if (paused) {
                emptyMap()
            } else {
                withContext(
                    ioDispatcher,
                ) { runCatching { launchLog.counts() }.getOrDefault(emptyMap()) }
            }
        }.distinctUntilChanged()

    /** FR-APPS-10: private-space apps are searchable only while the container is shown (settings field 20). */
    private val privateShown: Flow<Boolean> =
        currentSettings.map { it?.privateSpaceContainerHidden != true }.distinctUntilChanged()

    /**
     * Index input = the assembled list (shown + hidden apps, collation order), then the apps of an unlocked private
     * space (FR-SEARCH-03: a locked space has none, so it is never searchable). In memory only; private apps have no
     * launch counts (the log never stores them).
     */
    private val index: StateFlow<SearchIndex?> =
        combine(appList.appList, counts, privateShown) { list, c, p -> Triple(list, c, p) }
            .mapLatest { (list, c, private) ->
                val withPrivate = private && list.privateApps.isNotEmpty()
                // FR-APPS-10: transliterations of private labels must not outlive the unlocked space (memory only, and
                // only while it is open). The cache refills from the remaining labels on this pass.
                if (!withPrivate && indexedPrivate) translitCache.clear()
                indexedPrivate = withPrivate
                val apps = if (withPrivate) list.allApps + list.privateApps else list.allApps
                // Tie order = the assembled list's collation order, so no comparator is needed here.
                SearchIndex.build(apps, c, comparator = null, transliterate, translitCache)
            }.flowOn(defaultDispatcher)
            .catch { emit(SearchIndex.EMPTY) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val includeHidden: Flow<Boolean> =
        currentSettings.map { it?.showHiddenInSearch ?: true }.distinctUntilChanged()

    private data class Ranked(val query: String, val results: List<VisibleApp>)

    private val ranked: Flow<Ranked?> =
        combine(query, index, includeHidden) { q, idx, hidden -> Triple(q, idx, hidden) }
            .mapLatest { (q, idx, hidden) ->
                when {
                    idx == null -> {
                        null
                    }

                    q.isBlank() -> {
                        Ranked(q, emptyList())
                    }

                    else -> {
                        // T-PERF-05 device trace: ranking time per keystroke.
                        Trace.beginSection(TRACE_SEARCH_RANK)
                        try {
                            Ranked(q, idx.rank(q, hidden).map { it.app })
                        } finally {
                            Trace.endSection()
                        }
                    }
                }
            }.flowOn(defaultDispatcher)

    private val recents: Flow<List<VisibleApp>> =
        combine(launchChanges, index, currentSettings) { _, idx, s -> idx to s }
            .mapLatest { (idx, s) ->
                if (idx == null || s == null || !s.recentsEnabled) return@mapLatest emptyList()
                val keys =
                    withContext(
                        ioDispatcher,
                    ) { runCatching { launchLog.recents(MAX_RECENTS * 2) }.getOrDefault(emptyList()) }
                keys
                    .mapNotNull { idx.appFor(it) }
                    .filter { !it.hidden || s.showHiddenInSearch }
                    .take(MAX_RECENTS)
            }.distinctUntilChanged()

    private data class ContactResults(val query: String, val hits: List<ContactHit>)

    /** FR-SEARCH-06 on: the setting and the permission (settings field 15 is "effective only while granted"). */
    private val contactsOn: Flow<Boolean> =
        combine(
            currentSettings.map { it?.contactsSearchEnabled == true }.distinctUntilChanged(),
            contactsPermitted,
        ) { enabled, permitted -> enabled && permitted && contacts != null }.distinctUntilChanged()

    /**
     * ARCHITECTURE §8: contacts run beside the app ranking, cancelled by the next keystroke (`mapLatest`); the app
     * results never wait for them. Memory only.
     */
    private val contactResults: Flow<ContactResults?> =
        combine(query, contactsOn) { q, on -> q to on }
            .mapLatest { (q, on) ->
                val source = contacts
                if (!on || source == null || q.isBlank()) return@mapLatest ContactResults(q, emptyList())
                val hits =
                    try {
                        source.search(q, ContactsSource.MAX_RESULTS).take(ContactsSource.MAX_RESULTS)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                ContactResults(q, hits)
            }

    private data class Extras(
        val selected: Int,
        val store: Boolean,
        val contacts: ContactResults?,
        val hintSession: Boolean,
    )

    val state: StateFlow<SearchUiState> =
        combine(
            query,
            ranked.onStart { emit(null) },
            recents.onStart { emit(emptyList()) },
            combine(selected, storeAvailable, contactResults.onStart { emit(null) }, hintSession, ::Extras),
            currentSettings,
        ) { q, r, rec, extras, s ->
            val settled = r != null && r.query == q
            val results = if (settled) r.results else r?.results.orEmpty()
            val items = if (q.isBlank()) rec else results
            // Contact rows belong to the query they were found for; stale names never show under a new query.
            val contactHits =
                extras.contacts
                    ?.takeIf { it.query == q && q.isNotBlank() }
                    ?.hits
                    .orEmpty()
            val noResults = q.isNotBlank() && settled && results.isEmpty()
            val hint =
                contacts != null && s != null && !s.contactsSearchEnabled && noResults &&
                    (extras.hintSession || !s.contactsHintShown) && NameLikeQuery.matches(q)
            val selectable = items.size + if (q.isBlank()) 0 else contactHits.size
            SearchUiState(
                query = q,
                results = results,
                recents = rec,
                showStoreRow = extras.store && noResults,
                selected = extras.selected.coerceIn(0, (selectable - 1).coerceAtLeast(0)),
                badgeStyle = s?.badgeStyle ?: BadgeStyle.DOT,
                settled = settled || q.isBlank(),
                contacts = contactHits,
                showContactsHint = hint,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())

    init {
        // "Once" (INTERACTION_SPEC §8): the first time the hint shows, settings field 16 is set; the hint then stays
        // for the rest of this search session only.
        viewModelScope.launch {
            state
                .map { it.showContactsHint }
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    hintSession.value = true
                    if (currentSettings.value?.contactsHintShown != true) {
                        try {
                            settings.update { it.copy(contactsHintShown = true) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Unit // shown again next time; nothing else depends on it
                        }
                    }
                }
        }
    }

    fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.Open -> {
                hintSession.value = false
                refreshContactsPermission()
                setQuery(action.seed)
                viewModelScope.launch {
                    storeAvailable.value =
                        withContext(ioDispatcher) { runCatching { systemIntents.canSearchStore() }.getOrDefault(false) }
                }
            }

            is SearchAction.QueryChanged -> {
                setQuery(action.query)
            }

            is SearchAction.MoveSelection -> {
                val size = state.value.selectableCount
                if (size > 0) selected.update { (it + action.delta).coerceIn(0, size - 1) }
            }

            SearchAction.LaunchSelected -> {
                val s = state.value
                val app = s.items.getOrNull(s.selected)
                val contact = if (s.isBlank) null else s.contacts.getOrNull(s.selected - s.items.size)
                when {
                    app != null -> launch(app, null)
                    contact != null -> openContact(contact, null)
                    s.showStoreRow -> storeSearch()
                }
            }

            is SearchAction.OpenContact -> {
                openContact(action.contact, action.origin)
            }

            SearchAction.ContactsHint -> {
                effectChannel.trySend(SearchEffect.OpenSettings)
                close()
            }

            SearchAction.Resume -> {
                refreshContactsPermission()
            }

            is SearchAction.Launch -> {
                launch(action.app, action.origin)
            }

            SearchAction.StoreSearch -> {
                storeSearch()
            }

            is SearchAction.OpenAppInfo -> {
                launcher.openAppInfo(action.key, null)
            }

            SearchAction.Close -> {
                close()
            }
        }
    }

    private fun refreshContactsPermission() {
        contactsPermitted.value = contacts?.permission() == PermissionState.GRANTED
    }

    /** FR-SEARCH-06: the contacts app shows the contact; search closes like after an app launch (J-04). */
    private fun openContact(
        contact: ContactHit,
        origin: LaunchOrigin?,
    ) {
        val source = contacts ?: return
        if (source.open(
                contact,
                origin,
            ) is Outcome.Ok
        ) {
            close()
        } else {
            effectChannel.trySend(SearchEffect.ContactOpenFailed)
        }
    }

    private fun setQuery(text: String) {
        query.value = text
        selected.value = 0
    }

    private fun close() {
        hintSession.value = false
        setQuery("")
        effectChannel.trySend(SearchEffect.Close)
    }

    /** FR-APPS-02 from search: start on main, log asynchronously, then reset and close (J-04). */
    private fun launch(
        app: VisibleApp,
        origin: LaunchOrigin?,
    ) {
        if (app.paused) {
            effectChannel.trySend(SearchEffect.ProfilePaused(app))
            return
        }
        when (val result = launcher.launch(app.key, origin)) {
            is Outcome.Ok -> {
                viewModelScope.launch(ioDispatcher) { runCatching { launchLog.record(app.key, app.profileKind) } }
                close()
            }

            is Outcome.Err -> {
                if (result.error == LaunchError.PROFILE_PAUSED) {
                    effectChannel.trySend(SearchEffect.ProfilePaused(app))
                    return
                }
                effectChannel.trySend(SearchEffect.LaunchFailed(app.displayLabel, app.key, result.error))
                catalog.requestReconcile()
            }
        }
    }

    /** FR-SEARCH-07: hand the query to the store; no network in Strata (NFR-PRIV-01). */
    private fun storeSearch() {
        val q = query.value.trim()
        if (q.isEmpty()) return
        if (systemIntents.storeSearch(q) is Outcome.Ok) close()
    }
}
