package app.strata.launcher.feature.widgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.WidgetHeights
import app.strata.launcher.core.model.WidgetPlacement
import app.strata.launcher.core.model.WidgetPlacementState
import app.strata.launcher.core.model.WidgetProviderRef
import app.strata.launcher.core.platformapi.widgets.WidgetHostController
import app.strata.launcher.core.platformapi.widgets.WidgetRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the header's widget area shows (FR-WIDGET-01…04). */
data class WidgetAreaState(
    val placement: WidgetPlacement? = null,
    /** The provider's `minHeight` in dp (step 1, FR-WIDGET-03). */
    val minHeightDp: Int = DEFAULT_MIN_HEIGHT_DP,
    /**
     * Widgets are not inflated and the header shows the clock: safe mode (ARCHITECTURE §10), or the theme boot has not
     * finished yet, so whether this start is in safe mode is not known.
     */
    val suppressed: Boolean = false,
    val menuOpen: Boolean = false,
    val resizing: Boolean = false,
    /** The placement's ID was confirmed bound by the last check (provider info published): its view may be created. */
    val hostReady: Boolean = false,
) {
    /** The widget takes the header slot (hosted or as the placeholder). */
    val shown: Boolean get() = placement != null && !suppressed

    /** The clock is hidden: a shown, available widget that replaces it. A placeholder never hides the clock. */
    val replacesClock: Boolean get() = shown && placement?.replaceClock == true && placement.available

    companion object {
        const val DEFAULT_MIN_HEIGHT_DP = 40
    }
}

sealed interface WidgetAreaAction {
    data object OpenMenu : WidgetAreaAction

    data object CloseMenu : WidgetAreaAction

    data object StartResize : WidgetAreaAction

    data object Taller : WidgetAreaAction

    data object Shorter : WidgetAreaAction

    data object DoneResize : WidgetAreaAction

    data object ToggleClock : WidgetAreaAction

    /** Widget menu → Replace: the picker; the new widget takes this one's place. */
    data object Replace : WidgetAreaAction

    data object Remove : WidgetAreaAction

    /** Placeholder → Re-add: the bind flow for the same provider (FR-WIDGET-04). */
    data object ReAdd : WidgetAreaAction
}

sealed interface WidgetAreaEffect {
    /** Open S-WPICK; with [readd] the bind flow starts at once for that provider. */
    data class OpenPicker(
        val readd: WidgetProviderRef?,
    ) : WidgetAreaEffect
}

/**
 * The header widget area (FR-WIDGET-01/03/04): placement, availability checks, the widget menu ([Resize] [Replace]
 * [Remove], plus the clock choice), resize steps and the placeholder. [refresh] runs at home's `onStart` and on host
 * events: a placement whose ID is no longer bound becomes the placeholder, and IDs of this host that no placement
 * uses are freed. Repository writes publish through its flow; nothing here blocks the main thread.
 */
class WidgetAreaViewModel(
    private val host: WidgetHostController,
    private val repository: WidgetRepository,
    private val logger: Logger,
    /** True while widgets must not be inflated (safe mode, or before the theme boot result is known). */
    suppressed: Flow<Boolean> = flowOf(false),
) : ViewModel() {
    private data class Local(
        val minHeightDp: Int = WidgetAreaState.DEFAULT_MIN_HEIGHT_DP,
        val menuOpen: Boolean = false,
        val resizing: Boolean = false,
        val verifiedId: Int? = null,
    )

    private val local = MutableStateFlow(Local())
    private val effectChannel = Channel<WidgetAreaEffect>(Channel.BUFFERED)
    val effects: Flow<WidgetAreaEffect> = effectChannel.receiveAsFlow()
    private val refreshLock = Mutex()

    val state: StateFlow<WidgetAreaState> =
        combine(
            repository.placement.catch { emit(null) },
            // Fail closed: a suppression flow that fails keeps widgets off.
            suppressed.catch { emit(true) }.distinctUntilChanged(),
            local,
        ) { placement, off, l ->
            WidgetAreaState(
                placement = placement,
                minHeightDp = l.minHeightDp,
                suppressed = off,
                hostReady = placement != null && placement.available && placement.appWidgetId == l.verifiedId,
                menuOpen = l.menuOpen && placement != null,
                resizing = l.resizing && placement?.available == true,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, WidgetAreaState())

    init {
        viewModelScope.launch { host.events.collect { refresh() } }
        // A new or remapped placement (picker, restore receiver) is checked at once.
        viewModelScope.launch {
            repository.placement
                .catch { emit(null) }
                .map { it?.appWidgetId }
                .distinctUntilChanged()
                .collect { refresh() }
        }
    }

    /** Checks the placement against the host and frees orphan IDs (home `onStart`, host events). */
    fun refresh() {
        viewModelScope.launch {
            refreshLock.withLock {
                try {
                    check()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(TAG, "widget check failed", e)
                }
            }
        }
    }

    private suspend fun check() {
        val p = repository.current()
        var valid = false
        if (p != null) {
            val bound = host.isBound(p.appWidgetId)
            if (p.state == WidgetPlacementState.BOUND && !bound) {
                logger.i(TAG, "widget provider unavailable")
                repository.setState(p.appWidgetId, WidgetPlacementState.UNAVAILABLE)
            }
            if (bound) {
                valid = p.state == WidgetPlacementState.BOUND
                val min = host.minHeightDp(p.appWidgetId)
                // isBound published the provider info, so hostView needs no binder call on main.
                local.update { it.copy(minHeightDp = min ?: it.minHeightDp, verifiedId = p.appWidgetId) }
            }
        }
        // A placement that is not bound may still be waiting for its restore remap: then its new ID is kept. IDs a bind
        // flow holds (allocated, not placed yet) are never orphans.
        if (p == null || valid) {
            val inFlight = host.inFlightIds()
            host
                .hostIds()
                .filter { it != p?.appWidgetId && it !in inFlight }
                .forEach { host.delete(it) }
        }
    }

    fun onAction(action: WidgetAreaAction) {
        val p = state.value.placement
        when (action) {
            WidgetAreaAction.OpenMenu -> {
                if (p != null) local.update { it.copy(menuOpen = true, resizing = false) }
            }

            WidgetAreaAction.CloseMenu -> {
                local.update { it.copy(menuOpen = false) }
            }

            WidgetAreaAction.StartResize -> {
                local.update { it.copy(menuOpen = false, resizing = true) }
            }

            WidgetAreaAction.DoneResize -> {
                local.update { it.copy(resizing = false) }
            }

            WidgetAreaAction.Taller -> {
                if (p != null) step(p, p.heightStep + 1)
            }

            WidgetAreaAction.Shorter -> {
                if (p != null) step(p, p.heightStep - 1)
            }

            WidgetAreaAction.ToggleClock -> {
                local.update { it.copy(menuOpen = false) }
                if (p != null) write { repository.setReplaceClock(p.appWidgetId, !p.replaceClock) }
            }

            WidgetAreaAction.Replace -> {
                local.update { it.copy(menuOpen = false) }
                effectChannel.trySend(WidgetAreaEffect.OpenPicker(readd = null))
            }

            WidgetAreaAction.ReAdd -> {
                local.update { it.copy(menuOpen = false) }
                if (p != null) effectChannel.trySend(WidgetAreaEffect.OpenPicker(readd = p.provider))
            }

            WidgetAreaAction.Remove -> {
                local.update { it.copy(menuOpen = false, resizing = false) }
                if (p != null) {
                    write {
                        repository.remove(p.appWidgetId)
                        host.delete(p.appWidgetId)
                    }
                }
            }
        }
    }

    private fun step(
        p: WidgetPlacement,
        next: Int,
    ) {
        val clamped = WidgetHeights.clampStep(next)
        if (clamped != p.heightStep) write { repository.setHeightStep(p.appWidgetId, clamped) }
    }

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "widget change not saved", e)
            }
        }
    }

    private companion object {
        const val TAG = "WidgetArea"
    }
}
