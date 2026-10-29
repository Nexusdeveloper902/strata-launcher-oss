package app.strata.launcher.core.data.list

import app.strata.launcher.core.data.LazyResource
import app.strata.launcher.core.data.db.StrataDatabase
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.AppOverride
import app.strata.launcher.core.platformapi.apps.AppCatalog
import app.strata.launcher.core.platformapi.apps.AppListSource
import app.strata.launcher.core.platformapi.system.Collation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn

/** `app_override` rows as a map (ARCHITECTURE §4 `OverridesRepository`, read side; writes arrive in M3). */
fun overridesFlow(database: LazyResource<StrataDatabase>): Flow<Map<AppKey, AppOverride>> =
    flow { emitAll(database.get().overrideDao().observeAll()) }
        .map { rows ->
            rows.associate { r ->
                val key = AppKey(r.component, r.userSerial)
                key to AppOverride(key, hidden = r.hidden, customLabel = r.customLabel, missingSince = r.missingSince)
            }
        }

/**
 * The single [AppListAssembler] pipeline shared by home, list and search: recomputed on
 * `Dispatchers.Default` whenever the catalog, the overrides or the locales change (latest wins).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssembledAppListSource(
    catalog: AppCatalog,
    overrides: Flow<Map<AppKey, AppOverride>>,
    locales: StateFlow<List<String>>,
    collation: Collation,
    defaultDispatcher: CoroutineDispatcher,
    scope: CoroutineScope,
    /** True once the persisted snapshot has been read; nothing is emitted before (FR-LIFE-01). */
    snapshotLoaded: StateFlow<Boolean> = MutableStateFlow(true),
) : AppListSource {
    override val appList: Flow<AppList> =
        combine(
            catalog.catalog,
            overrides.catch { emit(emptyMap()) }.distinctUntilChanged(),
            locales,
            snapshotLoaded,
        ) { c, o, l, ready -> if (ready) Triple(c, o, l) else null }
            .filterNotNull()
            .mapLatest { (c, o, l) ->
                AppListAssembler.assemble(c, o, collation.sections(l), collation.comparator(l))
            }.distinctUntilChanged()
            .flowOn(defaultDispatcher)
            .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
