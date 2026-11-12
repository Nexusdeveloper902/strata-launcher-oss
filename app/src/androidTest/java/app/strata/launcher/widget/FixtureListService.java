package app.strata.launcher.widget;

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import app.strata.launcher.test.R;

/** Test APK only: the rows of {@link FixtureWidgetProvider}'s list ("Fixture row 0" …). Plain Java (see the provider). */
public class FixtureListService extends RemoteViewsService {
    public static final int ROWS = 40;

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        final Context context = getApplicationContext();
        return new RemoteViewsFactory() {
            @Override
            public void onCreate() {}

            @Override
            public void onDataSetChanged() {}

            @Override
            public void onDestroy() {}

            @Override
            public int getCount() {
                return ROWS;
            }

            @Override
            public RemoteViews getViewAt(int position) {
                RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.fixture_widget_row);
                row.setTextViewText(R.id.fixture_row, "Fixture row " + position);
                return row;
            }

            @Override
            public RemoteViews getLoadingView() {
                return null;
            }

            @Override
            public int getViewTypeCount() {
                return 1;
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public boolean hasStableIds() {
                return true;
            }
        };
    }
}
