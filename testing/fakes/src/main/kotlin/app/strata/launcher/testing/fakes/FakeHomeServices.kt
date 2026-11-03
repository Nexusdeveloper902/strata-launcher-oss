package app.strata.launcher.testing.fakes

import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppOverride
import app.strata.launcher.core.model.IconImage
import app.strata.launcher.core.model.IconRequest
import app.strata.launcher.core.model.IconResult
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.platformapi.apps.AddFavoriteResult
import app.strata.launcher.core.platformapi.apps.FavoritesRepository
import app.strata.launcher.core.platformapi.apps.LabelRules
import app.strata.launcher.core.platformapi.apps.OverridesRepository
import app.strata.launcher.core.platformapi.icons.IconProvider
import app.strata.launcher.core.platformapi.system.LockService
import app.strata.launcher.core.platformapi.system.TimeTicks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update

/** In-memory [OverridesRepository] with the same PRIVATE guard and label rules as the Room one. */
class FakeOverridesRepository(initial: Map<AppKey, AppOverride> = emptyMap()) : OverridesRepository {
    val state = MutableStateFlow(initial)

    override val overrides: StateFlow<Map<AppKey, AppOverride>> = state

    override suspend fun setHidden(
        key: AppKey,
        profileKind: ProfileKind,
        hidden: Boolean,
    ) {
        if (profileKind == ProfileKind.PRIVATE) return
        edit(key) { it.copy(hidden = hidden) }
    }

    override suspend fun setCustomLabel(
        key: AppKey,
        profileKind: ProfileKind,
        label: String?,
    ) {
        if (profileKind == ProfileKind.PRIVATE) return
        edit(key) { it.copy(customLabel = label?.let(LabelRules::normalize)) }
    }

    private fun edit(
        key: AppKey,
        transform: (AppOverride) -> AppOverride,
    ) = state.update { map ->
        val next = transform(map[key] ?: AppOverride(key))
        if (!next.hidden && next.customLabel == null) map - key else map + (key to next)
    }
}

/** In-memory [FavoritesRepository] with the repository's cap rule. */
class FakeFavoritesRepository(initial: List<AppKey> = emptyList()) : FavoritesRepository {
    val state = MutableStateFlow(initial)

    override val favorites: StateFlow<List<AppKey>> = state

    override suspend fun add(
        key: AppKey,
        isShown: (AppKey) -> Boolean,
    ): AddFavoriteResult {
        val current = state.value
        return when {
            key in current -> AddFavoriteResult.ALREADY_FAVORITE
            !isShown(key) -> AddFavoriteResult.NOT_SHOWN
            current.count(isShown) >= FavoritesRepository.MAX -> AddFavoriteResult.FULL
            else -> AddFavoriteResult.ADDED.also { state.value = current + key }
        }
    }

    override suspend fun remove(key: AppKey) = state.update { it - key }

    override suspend fun replace(
        old: AppKey,
        new: AppKey,
        isShown: (AppKey) -> Boolean,
    ) = state.update { current ->
        val without = current.filterNot { it == new }
        val i = without.indexOf(old)
        when {
            old == new -> current
            i >= 0 -> without.toMutableList().also { it[i] = new }
            new in current -> current
            !isShown(new) || current.count(isShown) >= FavoritesRepository.MAX -> current
            else -> current + new
        }
    }

    override suspend fun reorder(order: List<AppKey>) =
        state.update { current ->
            val ordered = order.distinct().filter { it in current }
            ordered + current.filterNot { it in ordered }
        }

    var saveEditCalls: Int = 0
        private set

    override suspend fun saveEdit(
        order: List<AppKey>,
        edited: Set<AppKey>,
    ) {
        saveEditCalls++
        state.update { FavoritesRepository.merge(it, order, edited) }
    }
}

/** Ticks on demand. */
class FakeTimeTicks : TimeTicks {
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    override val ticks: Flow<Unit> = events.onStart { emit(Unit) }

    fun tick() {
        events.tryEmit(Unit)
    }
}

/** Records lock requests; [enabled] is set by tests. */
class FakeLockService(
    override val supported: Boolean = true,
    enabled: Boolean = false,
    /** False = enabled in the system settings but the service has not connected yet. */
    var connected: Boolean = true,
) : LockService {
    override val enabled = MutableStateFlow(enabled)
    var locks: Int = 0
        private set

    override fun refresh() = Unit

    override fun lock(): Boolean {
        if (!enabled.value || !connected) return false
        locks++
        return true
    }
}

/** Always the monogram (pure JVM: no bitmaps); counts requests. */
class FakeIconProvider : IconProvider {
    val requests = mutableListOf<IconRequest>()

    override fun cached(req: IconRequest): IconImage? = null

    override suspend fun icon(req: IconRequest): IconResult {
        requests += req
        return IconResult.Monogram
    }

    override fun trim(critical: Boolean) = Unit
}
