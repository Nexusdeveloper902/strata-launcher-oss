package app.strata.launcher.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;
import app.strata.launcher.test.R;

/**
 * Test APK only: the WIDGET_FIX provider of the M7 widget device tests (WidgetDeviceTest). A scrollable list of
 * {@link FixtureListService#ROWS} rows, no configure activity (T-WIDGET-01, T-GEST-02). Its subclasses only differ in
 * their provider XML, which names a configure activity (T-WIDGET-04/05).
 *
 * <p>Plain Java with framework APIs only: it runs in the test package's own process, where the Kotlin standard library
 * is not available (docs/impl-notes/M7.md, review fix 7).
 */
public class FixtureWidgetProvider extends AppWidgetProvider {
    @Override
    @SuppressWarnings("deprecation") // setRemoteAdapter(int, Intent): the collection API that exists on API 26.
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.fixture_widget_list);
            Intent service = new Intent(context, FixtureListService.class)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            service.setData(Uri.parse(service.toUri(Intent.URI_INTENT_SCHEME)));
            views.setRemoteAdapter(R.id.fixture_list, service);
            views.setEmptyView(R.id.fixture_list, R.id.fixture_empty);
            manager.updateAppWidget(id, views);
        }
    }

    /** Configure activity that the user leaves with Back (RESULT_CANCELED, T-WIDGET-04). */
    public static class BackConfig extends FixtureWidgetProvider {}

    /** Configure activity that answers RESULT_FIRST_USER (T-WIDGET-05). */
    public static class FirstUserConfig extends FixtureWidgetProvider {}

    /** Configure activity whose process dies (T-WIDGET-05). */
    public static class CrashConfig extends FixtureWidgetProvider {}
}
