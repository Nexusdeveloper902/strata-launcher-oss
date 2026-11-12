package app.strata.launcher.testing.fakes

import app.strata.launcher.core.model.IconImage
import app.strata.launcher.core.model.WidgetHeights
import app.strata.launcher.core.model.WidgetPlacement
import app.strata.launcher.core.model.WidgetPlacementState
import app.strata.launcher.core.model.WidgetProviderRef
import app.strata.launcher.core.platformapi.widgets.BindError
import app.strata.launcher.core.platformapi.widgets.BindStep
import app.strata.launcher.core.platformapi.widgets.WidgetHostController
import app.strata.launcher.core.platformapi.widgets.WidgetHostEvent
import app.strata.launcher.core.platformapi.widgets.WidgetPreview
import app.strata.launcher.core.platformapi.widgets.WidgetProviderModel
import app.strata.launcher.core.platformapi.widgets.WidgetRepository
import app.strata.launcher.core.platformapi.widgets.WidgetViewHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [WidgetHostController]. [providerList] is what is installed; [silentBind] says whether
 * `bindAppWidgetIdIfAllowed` succeeds without consent. [boundIds] mirrors `AppWidgetHost.getAppWidgetIds()`.
 */
class FakeWidgetHost(
    var providerList: List<WidgetProviderModel> = emptyList(),
    var silentBind: Boolean = true,
) : WidgetHostController {
    private var nextId = 1
    val boundIds = linkedSetOf<Int>()

    /** IDs allocated but not bound yet (waiting for consent). */
    val allocated = linkedSetOf<Int>()
    val providerOf = mutableMapOf<Int, WidgetProviderRef>()
    val deleted = mutableListOf<Int>()
    var listening = false
        private set
    var configureStarts = 0
        private set
    var consentRequests = 0
        private set

    /** When false, [startConfigure] reports that the configure activity could not start. */
    var configureStartable = true
    val eventFlow = MutableSharedFlow<WidgetHostEvent>(extraBufferCapacity = 8)
    override val events: Flow<WidgetHostEvent> = eventFlow

    /** Simulates the provider's package going away: its widgets stop being bound. */
    fun loseProvider(ref: WidgetProviderRef) {
        providerOf.filterValues { it == ref }.keys.forEach { boundIds.remove(it) }
        eventFlow.tryEmit(WidgetHostEvent.PROVIDERS_CHANGED)
    }

    /** Binds [ref] to a fresh ID directly (test setup). */
    fun bindDirect(ref: WidgetProviderRef): Int {
        val id = nextId++
        boundIds += id
        providerOf[id] = ref
        return id
    }

    private fun model(ref: WidgetProviderRef) = providerList.firstOrNull { it.ref == ref }

    override suspend fun providers(): List<WidgetProviderModel> = providerList

    override suspend fun preview(
        ref: WidgetProviderRef,
        maxWidthPx: Int,
        maxHeightPx: Int,
    ): WidgetPreview = WidgetPreview.None

    override suspend fun icon(
        ref: WidgetProviderRef,
        sizePx: Int,
    ): IconImage? = null

    /** IDs allocated by [allocateAndBind] and not settled or deleted. */
    val inFlight = linkedSetOf<Int>()
    val sizes = mutableMapOf<Int, Pair<Float, Float>>()

    override fun inFlightIds(): Set<Int> = inFlight.toSet()

    override fun settled(id: Int) {
        inFlight.remove(id)
    }

    override suspend fun updateSize(
        id: Int,
        widthDp: Float,
        heightDp: Float,
    ) {
        sizes[id] = widthDp to heightDp
    }

    override suspend fun allocateAndBind(ref: WidgetProviderRef): BindStep {
        model(ref) ?: return BindStep.Failed(BindError.PROVIDER_GONE)
        val id = nextId++
        providerOf[id] = ref
        inFlight += id
        return if (silentBind) {
            boundIds += id
            afterBind(id)
        } else {
            allocated += id
            BindStep.NeedsConsent(id)
        }
    }

    private fun afterBind(id: Int): BindStep {
        val ref = providerOf[id] ?: return BindStep.Failed(BindError.HOST_FAILED)
        return if (model(ref)?.needsConfigure == true) BindStep.NeedsConfigure(id) else BindStep.Bound(id)
    }

    override suspend fun onConsentResult(
        id: Int,
        ok: Boolean,
    ): BindStep {
        allocated.remove(id)
        if (!ok) {
            delete(id)
            return BindStep.Failed(BindError.DENIED)
        }
        boundIds += id
        return afterBind(id)
    }

    override suspend fun onConfigureResult(
        id: Int,
        ok: Boolean,
    ): BindStep {
        if (!ok || id !in boundIds) {
            delete(id)
            return BindStep.Failed(BindError.CONFIGURE_CANCELLED)
        }
        return BindStep.Bound(id)
    }

    override suspend fun delete(id: Int) {
        deleted += id
        inFlight.remove(id)
        boundIds.remove(id)
        allocated.remove(id)
    }

    override suspend fun isBound(id: Int): Boolean = id in boundIds

    override suspend fun hostIds(): List<Int> = (boundIds + allocated).toList()

    override suspend fun minHeightDp(id: Int): Int? = providerOf[id]?.let { model(it)?.minHeightDp } ?: 40

    override fun consentRequest(
        id: Int,
        ref: WidgetProviderRef,
    ): Any? {
        consentRequests++
        return "consent:$id"
    }

    override fun startConfigure(
        activity: Any,
        id: Int,
        requestCode: Int,
    ): Boolean {
        configureStarts++
        return configureStartable
    }

    override fun hostView(id: Int): WidgetViewHandle? = null

    override fun startListening() {
        listening = true
    }

    override fun stopListening() {
        listening = false
    }
}

/** In-memory [WidgetRepository] with the Room repository's one-row semantics. */
class FakeWidgetRepository(
    initial: WidgetPlacement? = null,
) : WidgetRepository {
    val state = MutableStateFlow(initial)
    override val placement: Flow<WidgetPlacement?> = state

    override suspend fun current(): WidgetPlacement? = state.value

    override suspend fun place(placement: WidgetPlacement): List<Int> {
        val old = listOfNotNull(state.value?.appWidgetId).filter { it != placement.appWidgetId }
        state.value = placement.copy(heightStep = WidgetHeights.clampStep(placement.heightStep))
        return old
    }

    private fun edit(
        id: Int,
        f: (WidgetPlacement) -> WidgetPlacement,
    ) {
        val p = state.value ?: return
        if (p.appWidgetId == id) state.value = f(p)
    }

    override suspend fun setHeightStep(
        id: Int,
        step: Int,
    ) = edit(id) { it.copy(heightStep = WidgetHeights.clampStep(step)) }

    override suspend fun setReplaceClock(
        id: Int,
        replaceClock: Boolean,
    ) = edit(id) { it.copy(replaceClock = replaceClock) }

    override suspend fun setState(
        id: Int,
        state: WidgetPlacementState,
    ) = edit(id) { it.copy(state = state) }

    override suspend fun remove(id: Int) {
        if (state.value?.appWidgetId == id) state.value = null
    }

    override suspend fun remap(map: Map<Int, Int?>) {
        val p = state.value ?: return
        if (p.appWidgetId !in map) return
        val target = map[p.appWidgetId]
        state.value =
            if (target != null && target > 0) {
                p.copy(appWidgetId = target, state = WidgetPlacementState.BOUND)
            } else {
                p.copy(state = WidgetPlacementState.UNAVAILABLE)
            }
    }
}
