package app.strata.launcher.core.platformapi.widgets

import app.strata.launcher.core.model.IconImage
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.WidgetPlacement
import app.strata.launcher.core.model.WidgetPlacementState
import app.strata.launcher.core.model.WidgetProviderRef
import kotlinx.coroutines.flow.Flow

/** One installed widget provider for the picker (FR-WIDGET-02). Sizes are in dp. */
data class WidgetProviderModel(
    val ref: WidgetProviderRef,
    val label: String,
    /** Label of the app that provides it: the picker's group. */
    val appLabel: String,
    val profileKind: ProfileKind = ProfileKind.MAIN,
    val minWidthDp: Int,
    val minHeightDp: Int,
    /** The provider declares a configure activity that the host must run (ANDROID_PLATFORM §7). */
    val needsConfigure: Boolean = false,
)

/** Preview of a provider in the picker: an image, a generated preview (API 35+, an opaque `RemoteViews`), or none. */
sealed interface WidgetPreview {
    data class Image(
        val image: IconImage,
    ) : WidgetPreview

    /** [native] is an `android.widget.RemoteViews`; only `:app` unwraps it (same escape hatch as [WidgetViewHandle]). */
    class Generated(
        val native: Any,
    ) : WidgetPreview

    data object None : WidgetPreview
}

/** Why a widget was not added. Every failure has already deleted the allocated ID (ANDROID_PLATFORM §7, P-3). */
enum class BindError {
    /** The host could not allocate an ID, or the platform refused the call. */
    HOST_FAILED,

    /** The provider is not installed (any more). */
    PROVIDER_GONE,

    /** The user declined the system consent dialog. */
    DENIED,

    /** The configure activity was cancelled, failed, crashed or never answered. */
    CONFIGURE_CANCELLED,
}

/** One step of the bind flow (ARCHITECTURE §3 `BindStep`). */
sealed interface BindStep {
    /** Ready to place. */
    data class Bound(
        val id: Int,
    ) : BindStep

    /** Start the system consent dialog ([WidgetHostController.consentRequest]) and report its answer. */
    data class NeedsConsent(
        val id: Int,
    ) : BindStep

    /** Start the configure activity ([WidgetHostController.startConfigure]) and report its answer. */
    data class NeedsConfigure(
        val id: Int,
    ) : BindStep

    data class Failed(
        val error: BindError,
    ) : BindStep
}

/** Host events that may change a placement's availability (provider removed, providers changed). */
enum class WidgetHostEvent { PROVIDERS_CHANGED, WIDGET_REMOVED }

/**
 * The opaque host view of one widget ID. [native] is an `android.appwidget.AppWidgetHostView`; only `:app`'s
 * `AndroidView` unwraps it (ARCHITECTURE §3), so feature modules never see a View.
 */
class WidgetViewHandle(
    val id: Int,
    val native: Any,
)

/**
 * The platform widget host (ARCHITECTURE §3, ANDROID_PLATFORM §7), implemented in `:platform:widgets`. Suspend
 * functions do their binder calls off the main thread; [hostView], [startListening] and [stopListening] are main-only.
 * Exceptions from the platform or a provider never escape: they become [BindStep.Failed] or a null/false result.
 */
interface WidgetHostController {
    /** Installed providers of every profile, minus those hidden from pickers. */
    suspend fun providers(): List<WidgetProviderModel>

    /** The provider's preview fitted in [maxWidthPx] × [maxHeightPx] (generated preview first on API 35+). */
    suspend fun preview(
        ref: WidgetProviderRef,
        maxWidthPx: Int,
        maxHeightPx: Int,
    ): WidgetPreview

    /** The provider's icon (the picker's group header), or null. */
    suspend fun icon(
        ref: WidgetProviderRef,
        sizePx: Int,
    ): IconImage?

    /** Allocates an ID and binds it silently when allowed; otherwise asks for consent. */
    suspend fun allocateAndBind(ref: WidgetProviderRef): BindStep

    /** The consent dialog for [id] answered [ok]; a refusal deletes the ID. */
    suspend fun onConsentResult(
        id: Int,
        ok: Boolean,
    ): BindStep

    /** The configure activity for [id] answered [ok]; anything else deletes the ID. */
    suspend fun onConfigureResult(
        id: Int,
        ok: Boolean,
    ): BindStep

    /** Frees [id] (removal, cancel, replacement). Never throws. */
    suspend fun delete(id: Int)

    /** True when [id] belongs to this host and is bound to an installed provider. */
    suspend fun isBound(id: Int): Boolean

    /** IDs the platform holds for this host (orphans are deleted at home start). */
    suspend fun hostIds(): List<Int>

    /** IDs a bind flow allocated and has not placed yet ([settled] or deleted): never orphans. */
    fun inFlightIds(): Set<Int>

    /** [id] is placed (or handed to the caller): it stops being in flight. */
    fun settled(id: Int)

    /** Passes the area's size to the provider (`updateAppWidgetSize`), off the main thread. */
    suspend fun updateSize(
        id: Int,
        widthDp: Float,
        heightDp: Float,
    )

    /** The provider's `minHeight` in dp for the bound [id], or null. */
    suspend fun minHeightDp(id: Int): Int?

    /**
     * The `ACTION_APPWIDGET_BIND` request (an `Intent`) for [id] and [ref]; `:app` starts it for a result. Null when
     * it cannot be built.
     */
    fun consentRequest(
        id: Int,
        ref: WidgetProviderRef,
    ): Any?

    /**
     * Starts the configure activity of [id] for a result on [activity] (an `Activity`) with [requestCode]. False when
     * it cannot be started (the caller then treats it as a cancel).
     */
    fun startConfigure(
        activity: Any,
        id: Int,
        requestCode: Int,
    ): Boolean

    /**
     * The kept host view of [id] (created once, re-attached on configuration changes), or null if it cannot be made.
     * Main only; makes no binder call: [isBound] must have confirmed [id] first.
     */
    fun hostView(id: Int): WidgetViewHandle?

    fun startListening()

    fun stopListening()

    val events: Flow<WidgetHostEvent>
}

/**
 * Widget placements (ARCHITECTURE §4: `WidgetRepository`, Room `widget_placement`). v1 has one widget area, so
 * there is at most one placement. Main-safe.
 */
interface WidgetRepository {
    val placement: Flow<WidgetPlacement?>

    suspend fun current(): WidgetPlacement?

    /** Makes [placement] the only one, in one transaction; returns the IDs it replaced (the caller deletes them). */
    suspend fun place(placement: WidgetPlacement): List<Int>

    suspend fun setHeightStep(
        id: Int,
        step: Int,
    )

    suspend fun setReplaceClock(
        id: Int,
        replaceClock: Boolean,
    )

    suspend fun setState(
        id: Int,
        state: WidgetPlacementState,
    )

    suspend fun remove(id: Int)

    /**
     * `APPWIDGET_HOST_RESTORED` (FR-WIDGET-04): placements whose ID is a key move to its value and become bound; a
     * null or invalid value leaves them unavailable.
     */
    suspend fun remap(map: Map<Int, Int?>)
}
