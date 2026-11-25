package app.strata.launcher.badges;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.net.Uri;
import android.os.Build;
import android.os.RemoteException;
import android.os.SystemClock;
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
    /** Optional, parallel to {@link #EXTRA_NAMES}: one invented phone number per contact ("" = none). FR-SEARCH-09. */
    public static final String EXTRA_PHONES = "phones";
    public static final String KIND_ALERTING = "alerting";
    public static final String KIND_SILENT = "silent";
    public static final String KIND_ONGOING = "ongoing";
    public static final String ACCOUNT_NAME = "strata-fixture";
    public static final String ACCOUNT_TYPE = "app.strata.launcher.test.fixture";

    private static final String SENDER = "app.strata.launcher";
    private static final String TAG = "AccessFixture";
    /** How long a contacts step waits for the provider to be published again (bounded by the sender's wait). */
    private static final long PROVIDER_WAIT_MS = 8_000L;
    private static final long PROVIDER_POLL_MS = 100L;
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
            String[] phones = intent.getStringArrayExtra(EXTRA_PHONES);
            seed(context, names == null ? new String[0] : names, phones == null ? new String[0] : phones);
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

    private static void seed(Context context, String[] names, String[] phones)
            throws OperationApplicationException, RemoteException {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
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
            if (i < phones.length && !phones[i].isEmpty()) {
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, back)
                        .withValue(ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phones[i])
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                        .build());
            }
        }
        try (ContentProviderClient provider = contactsProvider(context)) {
            provider.applyBatch(ops);
        }
    }

    private static void clear(Context context) throws RemoteException {
        Uri uri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
                .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
                .build();
        try (ContentProviderClient provider = contactsProvider(context)) {
            provider.delete(uri, null, null);
        }
    }

    /**
     * The contacts provider, waiting while it is not published. On API 26 the contacts provider process is replaced
     * shortly after boot; a ContentResolver call that lands in that gap throws "Unknown URL" before reaching any provider
     * (CI run 45512812674: CLEAR_CONTACTS, pid 5270 gone, 5351 publishing 0.4 s later). Waiting here keeps the step a
     * single attempt: an operation that reached the provider and failed still reports RESULT_FAILED, never re-sent.
     */
    private static ContentProviderClient contactsProvider(Context context) {
        long end = SystemClock.uptimeMillis() + PROVIDER_WAIT_MS;
        while (true) {
            ContentProviderClient client =
                    context.getContentResolver().acquireContentProviderClient(ContactsContract.AUTHORITY);
            if (client != null) return client;
            if (SystemClock.uptimeMillis() >= end) {
                throw new IllegalStateException("contacts provider not published after " + PROVIDER_WAIT_MS + " ms");
            }
            SystemClock.sleep(PROVIDER_POLL_MS);
        }
    }
}
