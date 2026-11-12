package app.strata.launcher.widget;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Test APK only: turns a WIDGET_FIX provider of the test package off and on again, the "uninstall / reinstall" of
 * T-WIDGET-09 (the system then drops the provider's widgets). The test package changes its own component, so no
 * permission is needed, and {@code DONT_KILL_APP} keeps every process alive (pm disable could stop the instrumented
 * process, which also hosts the test package). Plain Java; driven by ordered broadcasts whose result code confirms the
 * step. On API 34+ only Strata may drive it (shared broadcast identity), as for AccessFixtureReceiver.
 */
public class WidgetFixtureReceiver extends BroadcastReceiver {
    public static final int RESULT_NOT_DELIVERED = 0;
    public static final int RESULT_DONE = 1;
    public static final int RESULT_FAILED = 2;
    public static final int RESULT_REFUSED = 3;

    public static final String ACTION_SET_PROVIDER = "app.strata.launcher.test.SET_WIDGET_PROVIDER";
    public static final String EXTRA_CLASS = "class";
    public static final String EXTRA_ENABLED = "enabled";

    private static final String SENDER = "app.strata.launcher";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !SENDER.equals(getSentFromPackage())) {
            setResultCode(RESULT_REFUSED);
            return;
        }
        String cls = intent.getStringExtra(EXTRA_CLASS);
        if (!ACTION_SET_PROVIDER.equals(intent.getAction()) || cls == null || !cls.startsWith("app.strata.launcher.widget.Fixture")) {
            setResultCode(RESULT_FAILED);
            return;
        }
        try {
            int state = intent.getBooleanExtra(EXTRA_ENABLED, true)
                    ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            context.getPackageManager().setComponentEnabledSetting(
                    new ComponentName(context, cls), state, PackageManager.DONT_KILL_APP);
            setResultCode(RESULT_DONE);
        } catch (RuntimeException e) {
            setResultCode(RESULT_FAILED);
        }
    }
}
