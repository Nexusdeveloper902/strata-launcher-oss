package app.strata.launcher.badges

import android.accounts.Account
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract

/**
 * Test APK only: the fixture app of the M7 device tests. It is a component of the test APK, so it runs in the test
 * package's own process and uid: notifications it posts belong to `app.strata.launcher.test` (the "Strata shortcut
 * fixture" row), and contacts it writes use the test package's WRITE_CONTACTS grant, under a fixture account that
 * [ACTION_CLEAR_CONTACTS] removes again. Driven by explicit broadcasts from the instrumentation.
 */
class AccessFixtureReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            ACTION_POST -> {
                post(context, intent)
            }

            ACTION_CANCEL_ALL -> {
                context.getSystemService(NotificationManager::class.java).cancelAll()
            }

            ACTION_SEED_CONTACTS, ACTION_CLEAR_CONTACTS -> {
                val pending = goAsync()
                Thread {
                    try {
                        if (intent.action == ACTION_SEED_CONTACTS) {
                            seed(context, intent.getStringArrayExtra(EXTRA_NAMES)?.filterNotNull().orEmpty())
                        } else {
                            clear(context)
                        }
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    private fun post(
        context: Context,
        intent: Intent,
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTING, "Fixture alerting", NotificationManager.IMPORTANCE_DEFAULT),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SILENT, "Fixture silent", NotificationManager.IMPORTANCE_LOW),
        )
        val kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_ALERTING
        val notification =
            Notification
                .Builder(context, if (kind == KIND_SILENT) CHANNEL_SILENT else CHANNEL_ALERTING)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(intent.getStringExtra(EXTRA_TITLE) ?: "Fixture")
                .setContentText(intent.getStringExtra(EXTRA_TEXT) ?: "")
                .setOngoing(kind == KIND_ONGOING)
                .setOnlyAlertOnce(true)
                .build()
        nm.notify(intent.getIntExtra(EXTRA_ID, 1), notification)
    }

    private fun seed(
        context: Context,
        names: List<String>,
    ) {
        val ops = ArrayList<ContentProviderOperation>()
        for (name in names) {
            val back = ops.size
            ops +=
                ContentProviderOperation
                    .newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT.type)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT.name)
                    .build()
            ops +=
                ContentProviderOperation
                    .newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, back)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                    ).withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build()
        }
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    private fun clear(context: Context) {
        val uri =
            ContactsContract.RawContacts.CONTENT_URI
                .buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT.type)
                .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT.name)
                .build()
        context.contentResolver.delete(uri, null, null)
    }

    companion object {
        const val ACTION_POST = "app.strata.launcher.test.POST"
        const val ACTION_CANCEL_ALL = "app.strata.launcher.test.CANCEL_ALL"
        const val ACTION_SEED_CONTACTS = "app.strata.launcher.test.SEED_CONTACTS"
        const val ACTION_CLEAR_CONTACTS = "app.strata.launcher.test.CLEAR_CONTACTS"
        const val EXTRA_ID = "id"
        const val EXTRA_KIND = "kind"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_NAMES = "names"
        const val KIND_ALERTING = "alerting"
        const val KIND_SILENT = "silent"
        const val KIND_ONGOING = "ongoing"
        private const val CHANNEL_ALERTING = "fixture_alerting"
        private const val CHANNEL_SILENT = "fixture_silent"
        val ACCOUNT = Account("strata-fixture", "app.strata.launcher.test.fixture")
    }
}
