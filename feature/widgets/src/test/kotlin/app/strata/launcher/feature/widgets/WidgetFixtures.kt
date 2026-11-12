package app.strata.launcher.feature.widgets

import app.strata.launcher.core.model.WidgetProviderRef
import app.strata.launcher.core.platformapi.widgets.WidgetProviderModel

/** Three providers from two apps (T-WIDGET-02), one with a configure activity. */
object WidgetFixtures {
    val clock =
        WidgetProviderModel(
            WidgetProviderRef("fixture.clock/.Clock", 0L),
            "Clock",
            "Clock app",
            minWidthDp = 100,
            minHeightDp = 60,
        )
    val alarm =
        WidgetProviderModel(
            WidgetProviderRef("fixture.clock/.Alarm", 0L),
            "Alarm",
            "Clock app",
            minWidthDp = 100,
            minHeightDp = 40,
        )
    val notes =
        WidgetProviderModel(
            WidgetProviderRef("fixture.notes/.Note", 0L),
            "Note",
            "Notes",
            minWidthDp = 100,
            minHeightDp = 80,
            needsConfigure = true,
        )
    val all = listOf(notes, clock, alarm)
}
