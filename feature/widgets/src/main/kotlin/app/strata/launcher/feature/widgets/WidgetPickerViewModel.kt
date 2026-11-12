package app.strata.launcher.feature.widgets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.WidgetHeights
import app.strata.launcher.core.model.WidgetPlacement
import app.strata.launcher.core.model.WidgetPlacementState
import app.strata.launcher.core.model.WidgetProviderRef
import app.strata.launcher.core.platformapi.widgets.BindStep
import app.strata.launcher.core.platformapi.widgets.WidgetHostController
import app.strata.launcher.core.platformapi.widgets.WidgetProviderModel
import app.strata.launcher.core.platformapi.widgets.WidgetRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

/** The providers of one app (FR-WIDGET-02: grouped by app). */
data class ProviderGroup(
    val appLabel: String,
    val packageName: String,
    val providers: List<WidgetProviderModel>,
)

data class WidgetPickerState(
    val loading: Boolean = true,
    val groups: List<ProviderGroup> = emptyList(),
    val query: String = "",
    /** A bind flow is running: picks are ignored. */
    val busy: Boolean = false,
    /** J-10: the "Replace clock?" choice of a first add. */
    val askReplaceClock: Boolean = false,
    /** Placed: the activity finishes. */
    val done: Boolean = false,
) {
    val visibleGroups: List<ProviderGroup>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return groups
            return groups.mapNotNull { g ->
                if (g.appLabel.contains(q, ignoreCase = true)) {
                    g
                } else {
                    g.providers.filter { it.label.contains(q, ignoreCase = true) }.takeIf { it.isNotEmpty() }?.let {
                        g.copy(providers = it)
                    }
                }
            }
        }
}

sealed interface WidgetPickerAction {
    data class Query(
        val text: String,
    ) : WidgetPickerAction

    data class Pick(
        val ref: WidgetProviderRef,
    ) : WidgetPickerAction

    /** The "Replace clock?" answer; null = dismissed (cancels the add). */
    data class ReplaceClock(
        val replace: Boolean?,
    ) : WidgetPickerAction
}

sealed interface WidgetPickerEffect {
    /** Start the consent request for [id] (`ACTION_APPWIDGET_BIND`) and report it with [WidgetPickerViewModel.onConsentResult]. */
    data class LaunchConsent(
        val id: Int,
        val ref: WidgetProviderRef,
    ) : WidgetPickerEffect

    /** Start the configure activity of [id] and report it with [WidgetPickerViewModel.onConfigureResult]. */
    data class LaunchConfigure(
        val id: Int,
    ) : WidgetPickerEffect

    /** J-10: snackbar "Widget not added". */
    data object NotAdded : WidgetPickerEffect
}

/**
 * S-WPICK and the bind flow of J-10 (FR-WIDGET-02): pick → bind (silent if allowed, else consent) → configure if
 * needed → "Replace clock?" on a first add → placed. Cancel at any step frees the ID ("Widget not added").
 *
 * The step in flight (ID, provider, stage) lives in the [handle], so the consent and configure answers still find
 * it after the activity is recreated or the process died while another app was in front. [readd] starts the flow
 * at once for that provider (FR-WIDGET-04 "Re-add"). [cleanup] outlives the view model: [abandon] frees an ID
 * when the activity finishes mid-flow.
 */
class WidgetPickerViewModel(
    private val host: WidgetHostController,
    private val repository: WidgetRepository,
    private val handle: SavedStateHandle,
    private val logger: Logger,
    private val cleanup: CoroutineScope,
    private val readd: WidgetProviderRef? = null,
) : ViewModel() {
    private val mutable = MutableStateFlow(WidgetPickerState())
    val state: StateFlow<WidgetPickerState> = mutable.asStateFlow()
    private val effectChannel = Channel<WidgetPickerEffect>(Channel.BUFFERED)
    val effects: Flow<WidgetPickerEffect> = effectChannel.receiveAsFlow()

    /** ANSWERED: the host is processing an answer for this ID; further answers are ignored. */
    private enum class Stage { CONSENT, CONFIGURE, ASK_CLOCK, ANSWERED }

    private data class Pending(
        val id: Int,
        val ref: WidgetProviderRef,
        val stage: Stage,
    )

    private var pending: Pending?
        get() {
            val id = handle.get<Int>(KEY_ID) ?: return null
            val component = handle.get<String>(KEY_COMPONENT) ?: return null
            val serial = handle.get<Long>(KEY_SERIAL) ?: return null
            val stage =
                handle.get<String>(KEY_STAGE)?.let { s -> Stage.entries.firstOrNull { it.name == s } } ?: return null
            return Pending(id, WidgetProviderRef(component, serial), stage)
        }
        set(value) {
            handle[KEY_ID] = value?.id
            handle[KEY_COMPONENT] = value?.ref?.component
            handle[KEY_SERIAL] = value?.ref?.userSerial
            handle[KEY_STAGE] = value?.stage?.name
        }

    init {
        // An answer that was being processed when the process died never completes: free its ID.
        pending?.takeIf { it.stage == Stage.ANSWERED }?.let { abandon() }
        val inFlight = pending
        mutable.update { it.copy(busy = inFlight != null, askReplaceClock = inFlight?.stage == Stage.ASK_CLOCK) }
        if (readd != null && handle.get<Boolean>(KEY_READD_STARTED) != true) {
            handle[KEY_READD_STARTED] = true
            onAction(WidgetPickerAction.Pick(readd))
        }
        loadProviders()
    }

    private fun loadProviders() {
        viewModelScope.launch {
            val list =
                try {
                    host.providers()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(TAG, "providers failed", e)
                    emptyList()
                }
            mutable.update { it.copy(loading = false, groups = group(list)) }
        }
    }

    fun onAction(action: WidgetPickerAction) {
        when (action) {
            is WidgetPickerAction.Query -> {
                mutable.update { it.copy(query = action.text) }
            }

            is WidgetPickerAction.Pick -> {
                if (mutable.value.busy || pending != null) return
                mutable.update { it.copy(busy = true) }
                viewModelScope.launch { advance(host.allocateAndBind(action.ref), action.ref) }
            }

            is WidgetPickerAction.ReplaceClock -> {
                val p = pending?.takeIf { it.stage == Stage.ASK_CLOCK } ?: return
                mutable.update { it.copy(askReplaceClock = false) }
                viewModelScope.launch {
                    val replace = action.replace
                    if (replace == null) {
                        host.delete(p.id)
                        fail()
                    } else {
                        place(p.id, p.ref, WidgetHeights.DEFAULT_STEP, replace)
                    }
                }
            }
        }
    }

    /** The answer of the consent dialog started for the pending ID (also after recreation or process death). */
    fun onConsentResult(ok: Boolean) {
        val p = take(Stage.CONSENT) ?: return
        viewModelScope.launch { advance(host.onConsentResult(p.id, ok), p.ref) }
    }

    /**
     * Takes the step in flight for [stage], synchronously (main): a second answer for the same step (a duplicate
     * activity result) then finds nothing and is ignored. The ID is still recorded as consumed only once the host has
     * answered, so [abandon] still frees it if the activity finishes meanwhile.
     */
    private fun take(stage: Stage): Pending? {
        val p = pending?.takeIf { it.stage == stage } ?: return null
        pending = p.copy(stage = Stage.ANSWERED)
        return p
    }

    /** The answer of the configure activity; anything but OK (cancel, error, crash) is a cancel. */
    fun onConfigureResult(ok: Boolean) {
        val p = take(Stage.CONFIGURE) ?: return
        viewModelScope.launch { advance(host.onConfigureResult(p.id, ok), p.ref) }
    }

    /** The activity is finishing with a step in flight (left, cleared by a Home press): the ID is freed. */
    fun abandon() {
        val p = pending ?: return
        pending = null
        cleanup.launch { host.delete(p.id) }
    }

    private suspend fun advance(
        step: BindStep,
        ref: WidgetProviderRef,
    ) {
        when (step) {
            is BindStep.Bound -> {
                val existing = repository.current()
                if (existing == null) {
                    pending = Pending(step.id, ref, Stage.ASK_CLOCK)
                    mutable.update { it.copy(askReplaceClock = true) }
                } else {
                    // Replace / Re-add keep the area's height and clock choice.
                    place(step.id, ref, existing.heightStep, existing.replaceClock)
                }
            }

            is BindStep.NeedsConsent -> {
                pending = Pending(step.id, ref, Stage.CONSENT)
                effectChannel.send(WidgetPickerEffect.LaunchConsent(step.id, ref))
            }

            is BindStep.NeedsConfigure -> {
                pending = Pending(step.id, ref, Stage.CONFIGURE)
                effectChannel.send(WidgetPickerEffect.LaunchConfigure(step.id))
            }

            is BindStep.Failed -> {
                logger.i(TAG, "widget not added: ${step.error}")
                fail()
            }
        }
    }

    private suspend fun fail() {
        pending = null
        mutable.update { it.copy(busy = false, askReplaceClock = false) }
        effectChannel.send(WidgetPickerEffect.NotAdded)
    }

    private suspend fun place(
        id: Int,
        ref: WidgetProviderRef,
        step: Int,
        replaceClock: Boolean,
    ) {
        // The ID leaves the step in flight before the write: abandon() must never delete an ID that is being placed.
        pending = null
        try {
            val replaced = repository.place(WidgetPlacement(id, ref, step, replaceClock, WidgetPlacementState.BOUND))
            host.settled(id)
            replaced.forEach { host.delete(it) }
            mutable.update { it.copy(busy = false, done = true) }
        } catch (e: CancellationException) {
            // Cancelled mid-write (the activity is gone): keep the ID only if the placement made it to storage.
            withContext(NonCancellable) {
                val stored = runCatching { repository.current()?.appWidgetId == id }.getOrDefault(false)
                if (stored) host.settled(id) else host.delete(id)
            }
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "placement not saved", e)
            host.delete(id)
            fail()
        }
    }

    companion object {
        private const val TAG = "WidgetPicker"
        private const val KEY_ID = "widget_pending_id"
        private const val KEY_COMPONENT = "widget_pending_component"
        private const val KEY_SERIAL = "widget_pending_serial"
        private const val KEY_STAGE = "widget_pending_stage"
        private const val KEY_READD_STARTED = "widget_readd_started"

        /** Groups by app label (locale order), providers by label inside a group. */
        fun group(list: List<WidgetProviderModel>): List<ProviderGroup> {
            val collator = Collator.getInstance()
            return list
                .groupBy { it.ref.packageName to it.appLabel }
                .map { (key, providers) ->
                    ProviderGroup(key.second, key.first, providers.sortedWith(compareBy(collator) { it.label }))
                }.sortedWith(compareBy(collator) { it.appLabel })
        }
    }
}
