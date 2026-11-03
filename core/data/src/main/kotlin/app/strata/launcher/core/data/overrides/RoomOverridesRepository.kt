package app.strata.launcher.core.data.overrides

import app.strata.launcher.core.data.LazyResource
import app.strata.launcher.core.data.db.StrataDatabase
import app.strata.launcher.core.data.list.overridesFlow
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppOverride
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.platformapi.apps.AddFavoriteResult
import app.strata.launcher.core.platformapi.apps.FavoritesRepository
import app.strata.launcher.core.platformapi.apps.LabelRules
import app.strata.launcher.core.platformapi.apps.OverridesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [OverridesRepository] over `app_override` (ARCHITECTURE §4). Each write is one read-modify-write
 * transaction; PRIVATE-profile writes are dropped (never persist private-space data, P-5).
 */
class RoomOverridesRepository(
    private val database: LazyResource<StrataDatabase>,
) : OverridesRepository {
    override val overrides: Flow<Map<AppKey, AppOverride>> = overridesFlow(database)

    override suspend fun setHidden(
        key: AppKey,
        profileKind: ProfileKind,
        hidden: Boolean,
    ) {
        if (profileKind == ProfileKind.PRIVATE) return
        database.get().overrideDao().edit(key.component, key.userSerial) { it.copy(hidden = hidden) }
    }

    override suspend fun setCustomLabel(
        key: AppKey,
        profileKind: ProfileKind,
        label: String?,
    ) {
        if (profileKind == ProfileKind.PRIVATE) return
        val normalized = label?.let { LabelRules.normalize(it) }
        database.get().overrideDao().edit(key.component, key.userSerial) { it.copy(customLabel = normalized) }
    }
}

/**
 * [FavoritesRepository] over the `favorite` table. Writes are serialised by a mutex and always rewrite the
 * whole order in one transaction, so positions stay dense (0…n-1).
 */
class RoomFavoritesRepository(
    private val database: LazyResource<StrataDatabase>,
) : FavoritesRepository {
    private val mutex = Mutex()

    override val favorites: Flow<List<AppKey>> =
        flow { emitAll(database.get().favoriteDao().observe()) }
            .map { rows -> rows.map { AppKey(it.component, it.userSerial) } }

    override suspend fun add(
        key: AppKey,
        isShown: (AppKey) -> Boolean,
    ): AddFavoriteResult =
        edit { current ->
            when {
                key in current -> current to AddFavoriteResult.ALREADY_FAVORITE
                !isShown(key) -> current to AddFavoriteResult.NOT_SHOWN
                current.count(isShown) >= FavoritesRepository.MAX -> current to AddFavoriteResult.FULL
                else -> (current + key) to AddFavoriteResult.ADDED
            }
        }

    override suspend fun remove(key: AppKey) {
        edit { current -> (current - key) to Unit }
    }

    override suspend fun replace(
        old: AppKey,
        new: AppKey,
        isShown: (AppKey) -> Boolean,
    ) {
        edit { current ->
            val without = current.filterNot { it == new }
            val i = without.indexOf(old)
            val next =
                when {
                    old == new -> current
                    i >= 0 -> without.toMutableList().also { it[i] = new }
                    new in current -> current
                    !isShown(new) || current.count(isShown) >= FavoritesRepository.MAX -> current
                    else -> current + new
                }
            next to Unit
        }
    }

    override suspend fun reorder(order: List<AppKey>) {
        edit { current ->
            val ordered = order.distinct().filter { it in current }
            (ordered + current.filterNot { it in ordered }) to Unit
        }
    }

    override suspend fun saveEdit(
        order: List<AppKey>,
        edited: Set<AppKey>,
    ) {
        edit { current -> FavoritesRepository.merge(current, order, edited) to Unit }
    }

    /** Every write: read, transform and replace inside one Room transaction, serialised by [mutex]. */
    private suspend fun <R> edit(transform: (List<AppKey>) -> Pair<List<AppKey>, R>): R =
        mutex.withLock {
            var result: R? = null
            database.get().favoriteDao().update { rows ->
                val current = rows.map { (c, s) -> AppKey(c, s) }
                val (next, r) = transform(current)
                result = r
                if (next != current) next.map { it.component to it.userSerial } else null
            }
            @Suppress("UNCHECKED_CAST")
            result as R
        }
}
