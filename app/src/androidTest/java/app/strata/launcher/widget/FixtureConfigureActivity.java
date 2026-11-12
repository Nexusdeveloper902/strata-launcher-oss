package app.strata.launcher.widget;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/**
 * Test APK only: configure activities of the WIDGET_FIX providers (T-WIDGET-04/05). Plain Java (see
 * {@link FixtureWidgetProvider}). The base shows a label and is left with Back, which answers RESULT_CANCELED.
 */
public class FixtureConfigureActivity extends Activity {
    public static final String LABEL = "Fixture configure";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        TextView text = new TextView(this);
        text.setText(LABEL);
        text.setTextSize(24f);
        setContentView(text);
    }

    /** Answers RESULT_FIRST_USER at once: not OK, so the host treats it as a cancel. */
    public static class FirstUser extends Activity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setResult(RESULT_FIRST_USER);
            finish();
        }
    }

    /**
     * Crashes in {@code onCreate}: the system finishes the crashed activity and the host gets RESULT_CANCELED. A real
     * crash, not {@code Process.killProcess}: a process killed with its activity on top is restarted by the activity
     * manager, which on API 26 relaunched it in a kill loop and never answered the host (CI run 43159847399). API 26
     * may show the "has stopped" dialog despite {@code hide_error_dialogs}; the test closes it.
     */
    public static class Crash extends Activity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            throw new IllegalStateException("WIDGET_FIX configure crash (expected by WidgetDeviceTest)");
        }
    }
}
