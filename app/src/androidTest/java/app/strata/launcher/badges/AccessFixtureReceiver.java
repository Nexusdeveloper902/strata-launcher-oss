package app.strata.launcher.badges;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.net.Uri;
import android.os.Build;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.util.Log;
import java.util.ArrayList;

/**
 * Test APK only: the fixture app of the M7 device tests (OptionalAccessDeviceTest). It is a component of the test APK,
 * so it runs in the test package's own process and uid: notifications it posts belong to `app.strata.launcher.test`
 * (the "Strata shortcut fixture" row), and contacts it writes use the test package's WRITE_CONTACTS grant, under a
 * fixture account that {@link #ACTION_CLEAR_CONTACTS} removes again.
 *
 * <p>Plain Java on purpose: the test APK does not package the Kotlin standard library (the tested app provides it), so
 * Kotlin code of the test APK only works inside the app's process. In its own process a Kotlin receiver failed with
 * {@code ClassNotFoundException: kotlin.jvm.internal.Intrinsics} (CI run 42958194210, API 36). Framework APIs only.
 *
 * <p>Driven by explicit ordered broadcasts; the result code tells the test that the step finished.
 */
public class AccessFixtureReceiver extends BroadcastReceiver {
    /** Result codes of the ordered broadcast; the sender starts with {@link #RESULT_NOT_DELIVERED}. */
    public static final int RESULT_NOT_DELIVERED = 0;
    public static final int RESULT_DONE = 1;
    public static final int RESULT_FAILED = 2;
    public static final int RESULT_REFUSED = 3;

    public static final String ACTION_POST = "app.strata.launcher.test.POST";
    public static final String ACTION_CANCEL_ALL = "app.strata.launcher.test.CANCEL_ALL";
    public static final String ACTION_SEED_CONTACTS = "app.strata.launcher.test.SEED_CONTACTS";
    public static final String ACTION_CLEAR_CONTACTS = "app.strata.launcher.test.CLEAR_CONTACTS";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_NAMES = "names";
    public static final String KIND_ALERTING = "alerting";
    public static final String KIND_SILENT = "silent";
    public static final String KIND_ONGOING = "ongoing";
    public static final String ACCOUNT_NAME = "strata-fixture";
    public static final String ACCOUNT_TYPE = "app.strata.launcher.test.fixture";

    private static final String SENDER = "app.strata.launcher";
    private static final String TAG = "AccessFixture";
    private static final String CHANNEL_ALERTING = "fixture_alerting";
    private static final String CHANNEL_SILENT = "fixture_silent";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        // API 34+: only Strata's instrumentation (which shares its identity) may drive the fixture. Below 34 the sender
        // cannot be identified; the receiver is exported because the sender is another uid (see the test manifest).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !SENDER.equals(getSentFromPackage())) {
            setResultCode(RESULT_REFUSED);
            return;
        }
        final PendingResult pending = goAsync();
        new Thread(() -> {
            int code;
            try {
                run(context, intent);
                code = RESULT_DONE;
            } catch (RuntimeException | OperationApplicationException | RemoteException e) {
                Log.w(TAG, "fixture " + intent.getAction() + " failed", e);
                code = RESULT_FAILED;
            }
            pending.setResultCode(code);
            pending.finish();
        }).start();
    }

    private static void run(Context context, Intent intent) throws OperationApplicationException, RemoteException {
        String action = intent.getAction();
        if (ACTION_POST.equals(action)) {
            post(context, intent);
        } else if (ACTION_CANCEL_ALL.equals(action)) {
            context.getSystemService(NotificationManager.class).cancelAll();
        } else if (ACTION_SEED_CONTACTS.equals(action)) {
            String[] names = intent.getStringArrayExtra(EXTRA_NAMES);
            seed(context, names == null ? new String[0] : names);
        } else if (ACTION_CLEAR_CONTACTS.equals(action)) {
            clear(context);
        } else {
            throw new IllegalArgumentException("unknown action");
        }
    }

    private static void post(Context context, Intent intent) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL_ALERTING, "Fixture alerting", NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL_SILENT, "Fixture silent", NotificationManager.IMPORTANCE_LOW));
        String kind = intent.getStringExtra(EXTRA_KIND);
        if (kind == null) kind = KIND_ALERTING;
        String title = intent.getStringExtra(EXTRA_TITLE);
        String text = intent.getStringExtra(EXTRA_TEXT);
        Notification notification =
                new Notification.Builder(context, KIND_SILENT.equals(kind) ? CHANNEL_SILENT : CHANNEL_ALERTING)
                        .setSmallIcon(android.R.drawable.stat_notify_chat)
                        .setContentTitle(title == null ? "Fixture" : title)
                        .setContentText(text == null ? "" : text)
                        .setOngoing(KIND_ONGOING.equals(kind))
                        .setOnlyAlertOnce(true)
                        .build();
        nm.notify(intent.getIntExtra(EXTRA_ID, 1), notification);
    }

    private static void seed(Context context, String[] names) throws OperationApplicationException, RemoteException {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        for (String name : names) {
            int back = ops.size();
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
                    .build());
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, back)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build());
        }
        context.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
    }

    private static void clear(Context context) {
        Uri uri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
                .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
                .build();
        context.getContentResolver().delete(uri, null, null);
    }
}
