package app.strata.launcher.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** FR-WIDGET-03 height steps (level U, T-WIDGET-06 bounds). */
class WidgetHeightsTest {
    @Test
    fun tWidget06_fourStepsFromMinimumToHalfTheWindow() {
        // Window 800 dp: max 400 dp; minimum 100 dp; steps 100, 200, 300, 400.
        val steps = (1..4).map { WidgetHeights.heightDp(it, 100, 800) }
        assertThat(steps).containsExactly(100, 200, 300, 400).inOrder()
    }

    @Test
    fun tWidget06_neverBelowMinimumNorAboveHalf() {
        assertThat(WidgetHeights.heightDp(0, 100, 800)).isEqualTo(100)
        assertThat(WidgetHeights.heightDp(-3, 100, 800)).isEqualTo(100)
        assertThat(WidgetHeights.heightDp(9, 100, 800)).isEqualTo(400)
        assertThat(WidgetHeights.clampStep(0)).isEqualTo(1)
        assertThat(WidgetHeights.clampStep(5)).isEqualTo(4)
    }

    @Test
    fun tWidget06_providerMinimumAboveHalfWins() {
        // A provider minimum taller than the cap: every step is the minimum (never below minHeight).
        assertThat((1..4).map { WidgetHeights.heightDp(it, 500, 800) }).containsExactly(500, 500, 500, 500)
    }

    @Test
    fun placementStateCodes_roundTrip_unknownIsUnavailable() {
        WidgetPlacementState.entries.forEach { assertThat(WidgetPlacementState.of(it.code)).isEqualTo(it) }
        assertThat(WidgetPlacementState.of(42)).isEqualTo(WidgetPlacementState.UNAVAILABLE)
        assertThat(WidgetProviderRef("a.b/.W", 0L).packageName).isEqualTo("a.b")
    }
}
