package app.strata.launcher.widget;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
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
     * Its process dies while it is in front ("crashes"). Killing the process instead of throwing keeps the system's
     * "has stopped" dialog away, which API 26 shows despite {@code hide_error_dialogs} (M7 review fix 7); for the
     * host the outcome is the same: RESULT_CANCELED.
     */
    public static class Crash extends Activity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            Process.killProcess(Process.myPid());
        }
    }
}
