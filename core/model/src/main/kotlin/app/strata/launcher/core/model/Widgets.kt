package app.strata.launcher.core.model

/**
 * A widget provider (FR-WIDGET-02): its receiver component (`package/class`, flattened) in the profile [userSerial].
 * Used to re-add a widget for the same provider (FR-WIDGET-04).
 */
data class WidgetProviderRef(
    val component: String,
    val userSerial: Long,
) {
    val packageName: String get() = component.substringBefore('/')
}

/** `widget_placement.state` (ARCHITECTURE §4): what the header shows for a placement. */
enum class WidgetPlacementState(
    val code: Int,
) {
    /** The ID is bound to its provider: the widget is shown. */
    BOUND(0),

    /** Provider gone, or a restore gave no new ID: the "Widget unavailable — Re-add / Remove" placeholder (FR-WIDGET-04). */
    UNAVAILABLE(1),

    /** Added by a backup restore (ARCHITECTURE §12.3 (e), M8): the same placeholder, offering Re-add. */
    NEEDS_REBIND(2),
    ;

    companion object {
        fun of(code: Int): WidgetPlacementState = entries.firstOrNull { it.code == code } ?: UNAVAILABLE
    }
}

/**
 * The one widget of the home header (FR-WIDGET-01): its bound ID, provider, height step (FR-WIDGET-03) and whether
 * it is shown in place of the clock.
 */
data class WidgetPlacement(
    val appWidgetId: Int,
    val provider: WidgetProviderRef,
    val heightStep: Int = WidgetHeights.DEFAULT_STEP,
    val replaceClock: Boolean = false,
    val state: WidgetPlacementState = WidgetPlacementState.BOUND,
) {
    val available: Boolean get() = state == WidgetPlacementState.BOUND
}

/**
 * Widget height steps (FR-WIDGET-03, VISUAL_SPEC §2): 4 steps from the provider's `minHeight` to 50 % of the window
 * height, evenly spaced. A provider minimum above the half-window cap wins (never below `minHeight`). Pure.
 */
object WidgetHeights {
    const val MIN_STEP = 1
    const val MAX_STEP = 4

    /** Proposed (M7 notes): a new widget starts at the provider minimum. */
    const val DEFAULT_STEP = MIN_STEP

    /** Fraction of the window height the tallest step may take. */
    const val MAX_FRACTION = 0.5f

    fun clampStep(step: Int): Int = step.coerceIn(MIN_STEP, MAX_STEP)

    /** Height in dp of [step] for a provider minimum of [minHeightDp] in a window [windowHeightDp] tall. */
    fun heightDp(
        step: Int,
        minHeightDp: Int,
        windowHeightDp: Int,
    ): Int {
        val min = minHeightDp.coerceAtLeast(1)
        val max = (windowHeightDp * MAX_FRACTION).toInt().coerceAtLeast(min)
        val s = clampStep(step) - MIN_STEP
        return min + (max - min) * s / (MAX_STEP - MIN_STEP)
    }

    /** Below this window height the widget is hidden (INTERACTION_SPEC §10, VISUAL_SPEC §8.1). */
    const val MIN_WINDOW_HEIGHT_DP = 360
}
