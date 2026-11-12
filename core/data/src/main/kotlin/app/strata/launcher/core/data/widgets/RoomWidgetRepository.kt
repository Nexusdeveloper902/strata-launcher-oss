package app.strata.launcher.core.data.widgets

import app.strata.launcher.core.data.LazyResource
import app.strata.launcher.core.data.db.StrataDatabase
import app.strata.launcher.core.data.db.WidgetPlacementEntity
import app.strata.launcher.core.model.WidgetHeights
import app.strata.launcher.core.model.WidgetPlacement
import app.strata.launcher.core.model.WidgetPlacementState
import app.strata.launcher.core.model.WidgetProviderRef
import app.strata.launcher.core.platformapi.widgets.WidgetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed [WidgetRepository] (ARCHITECTURE §4, `widget_placement`). The table holds the provider as a flattened
 * component plus the user serial; the platform owns whether the ID is still bound.
 */
class RoomWidgetRepository(
    private val database: LazyResource<StrataDatabase>,
) : WidgetRepository {
    private suspend fun dao() = database.get().widgetPlacementDao()

    override val placement: Flow<WidgetPlacement?> =
        flow { emitAll(dao().observe()) }
            .map { it?.toModel() }
            .distinctUntilChanged()

    override suspend fun current(): WidgetPlacement? = dao().all().minByOrNull { it.appWidgetId }?.toModel()

    override suspend fun place(placement: WidgetPlacement): List<Int> = dao().replaceAll(placement.toEntity())

    override suspend fun setHeightStep(
        id: Int,
        step: Int,
    ) = dao().setHeightStep(id, WidgetHeights.clampStep(step))

    override suspend fun setReplaceClock(
        id: Int,
        replaceClock: Boolean,
    ) = dao().setReplaceClock(id, replaceClock)

    override suspend fun setState(
        id: Int,
        state: WidgetPlacementState,
    ) = dao().setState(id, state.code)

    override suspend fun remove(id: Int) = dao().delete(id)

    override suspend fun remap(map: Map<Int, Int?>) =
        dao().remap(map, WidgetPlacementState.BOUND.code, WidgetPlacementState.UNAVAILABLE.code)
}

internal fun WidgetPlacementEntity.toModel() =
    WidgetPlacement(
        appWidgetId = appWidgetId,
        provider = WidgetProviderRef(provider, userSerial),
        heightStep = WidgetHeights.clampStep(heightStep),
        replaceClock = replaceClock,
        state = WidgetPlacementState.of(state),
    )

internal fun WidgetPlacement.toEntity() =
    WidgetPlacementEntity(
        appWidgetId = appWidgetId,
        provider = provider.component,
        userSerial = provider.userSerial,
        heightStep = WidgetHeights.clampStep(heightStep),
        replaceClock = replaceClock,
        state = state.code,
    )
