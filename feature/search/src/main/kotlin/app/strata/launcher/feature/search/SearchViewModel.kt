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
import app.strata.launcher.core.platformapi.system.SystemIntents
import app.strata.launcher.core.platformapi.system.TextFolding
import app.strata.launcher.core.search.SearchIndex
import app.strata.launcher.core.search.Transliterate
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
) {
    val isBlank: Boolean get() = query.isBlank()

    /** What the result list shows: recents for the blank query, else the ranked results. */
    val items: List<VisibleApp> get() = if (isBlank) recents else results

    val noResults: Boolean get() = !isBlank && settled && results.isEmpty()
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

    /** Esc / Back / close: back to the origin layer. */
    data object Close : SearchAction
}

sealed interface SearchEffect {
    /** Close the layer and return to the origin (Esc, Back, a launched app). */
    data object Close : SearchEffect

    data class LaunchFailed(val label: String, val key: AppKey, val error: LaunchError) : SearchEffect

    /** FR-APPS-09: a paused work app was chosen; home offers "Turn on work apps?". */
    data class ProfilePaused(val app: VisibleApp) : SearchEffect
}

/**
 * S-SEARCH (FR-SEARCH-01…05, -07; ARCHITECTURE §8). The index is rebuilt off main on every list, override
 * or usage change; each query is ranked with `mapLatest` on [defaultDispatcher] (latest wins).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    appList: AppListSource,
    settings: SettingsRepository,
    private val launchLog: LaunchLogRepository,
    private val launcher: AppLauncher,
    private val catalog: AppCatalog,
    private val textFolding: TextFolding,
    private val systemIntents: SystemIntents,
    private val defaultDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val query = MutableStateFlow("")
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

    val state: StateFlow<SearchUiState> =
        combine(
            query,
            ranked.onStart { emit(null) },
            recents.onStart { emit(emptyList()) },
            combine(selected, storeAvailable) { s, store -> s to store },
            currentSettings.map { it?.badgeStyle ?: BadgeStyle.DOT },
        ) { q, r, rec, (sel, store), badge ->
            val settled = r != null && r.query == q
            val results = if (settled) r.results else r?.results.orEmpty()
            val items = if (q.isBlank()) rec else results
            SearchUiState(
                query = q,
                results = results,
                recents = rec,
                showStoreRow = store && settled && q.isNotBlank() && results.isEmpty(),
                selected = sel.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                badgeStyle = badge,
                settled = settled || q.isBlank(),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())

    fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.Open -> {
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
                val size = state.value.items.size
                if (size > 0) selected.update { (it + action.delta).coerceIn(0, size - 1) }
            }

            SearchAction.LaunchSelected -> {
                val s = state.value
                val app = s.items.getOrNull(s.selected)
                when {
                    app != null -> launch(app, null)
                    s.showStoreRow -> storeSearch()
                }
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

    private fun setQuery(text: String) {
        query.value = text
        selected.value = 0
    }

    private fun close() {
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
