package app.strata.launcher.platform.system

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.common.log.LogLevel
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.platformapi.apps.LaunchError
import app.strata.launcher.core.platformapi.system.CallMode
import app.strata.launcher.core.platformapi.system.ContactHit
import app.strata.launcher.core.platformapi.system.PermissionState
import app.strata.launcher.core.platformapi.system.PhoneNumber
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** [AndroidContactsSource] against a fake contacts provider (level R): cancellation reaches a running query; URI checks. */
@RunWith(AndroidJUnit4::class)
class AndroidContactsSourceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Blocks every query until its signal is cancelled (or 10 s), like a slow provider on a 2 000-contact book. */
    class BlockingProvider : ContentProvider() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
            cancellationSignal: CancellationSignal?,
        ): Cursor {
            cancellationSignal?.setOnCancelListener { cancelled.countDown() }
            entered.countDown()
            if (cancelled.await(10, TimeUnit.SECONDS)) throw OperationCanceledException()
            return MatrixCursor(projection)
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = query(uri, projection, selection, selectionArgs, sortOrder, null)

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0
    }

    private class Messages : Logger {
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()

        override fun log(
            level: LogLevel,
            tag: String,
            message: String,
            throwable: Throwable?,
        ) {
            messages += message
        }
    }

    @Before
    fun grant() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
    }

    /** Review finding: a cancelled search (the next keystroke) must stop the provider query it is waiting on. */
    @Test
    fun cancellingTheCallerCancelsTheRunningQuery() {
        val provider = Robolectric.setupContentProvider(BlockingProvider::class.java, ContactsContract.AUTHORITY)
        val logger = Messages()
        val source = AndroidContactsSource(app, Dispatchers.IO, logger)
        runBlocking {
            val search = async(Dispatchers.Default) { source.search("ann", 5) }
            assertThat(provider.entered.await(5, TimeUnit.SECONDS)).isTrue()
            search.cancel()
            assertThat(provider.cancelled.await(5, TimeUnit.SECONDS)).isTrue()
            withTimeout(5_000) { search.join() }
            assertThat(search.isCancelled).isTrue()
        }
        assertThat(logger.messages.filter { it.contains("failed") }).isEmpty()
    }

    @Test
    fun opensOnlyContactLookupUris() {
        val source = AndroidContactsSource(app, Dispatchers.IO, Messages())

        fun open(uri: String) = source.open(ContactHit(uri, "Ann"), null)
        assertThat(open("content://com.android.contacts/contacts/lookup/0r1-ABC/1")).isEqualTo(Outcome.Ok(Unit))
        assertThat(open("http://com.android.contacts/contacts/lookup/0r1/1")).isNotEqualTo(Outcome.Ok(Unit))
        assertThat(open("content://com.android.contacts/raw_contacts/1")).isNotEqualTo(Outcome.Ok(Unit))
        assertThat(open("content://evil.provider/contacts/lookup/0r1/1")).isNotEqualTo(Outcome.Ok(Unit))
        assertThat(open("content://com.android.contacts/contacts/lookup")).isNotEqualTo(Outcome.Ok(Unit))
    }

    /**
     * Phone rows for contact 7 behind a lookup URI (T-SEARCH-26). The provider sorts; this one returns its rows in the
     * order given and records the query, so the test can check the sort the source asked for.
     */
    class PhoneProvider : ContentProvider() {
        var rows: List<Array<Any?>> = emptyList()
        var failWith: RuntimeException? = null
        var phoneSelectionArgs: List<String>? = null
        var phoneSort: String? = null

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            failWith?.let { throw it }
            val segments = uri.pathSegments
            if (segments.size >= 2 && segments[0] == "contacts" && segments[1] == "lookup") {
                return MatrixCursor(arrayOf(ContactsContract.Contacts._ID)).apply { addRow(arrayOf<Any?>(7L)) }
            }
            phoneSelectionArgs = selectionArgs?.toList()
            phoneSort = sortOrder
            val cursor = MatrixCursor(projection)
            rows.forEach { values ->
                val byColumn = PHONE_COLUMNS.zip(values.toList()).toMap()
                cursor.addRow(projection!!.map { byColumn[it] }.toTypedArray())
            }
            return cursor
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        companion object {
            val PHONE_COLUMNS =
                listOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL,
                    ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
                    ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
                )
        }
    }

    private val mobile = ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
    private val work = ContactsContract.CommonDataKinds.Phone.TYPE_WORK
    private val zed = ContactHit("content://com.android.contacts/contacts/lookup/fx0/7", "Zed Fixture")

    private fun started(): Intent? = shadowOf(app).nextStartedActivity

    /** T-SEARCH-26: DIAL → `ACTION_DIAL tel:`; never a permission needed. */
    @Test
    fun tSearch26_dialStartsTheDialer() {
        val source = AndroidContactsSource(app, Dispatchers.IO, Messages())
        assertThat(source.call("+1 555 0100", CallMode.DIAL)).isEqualTo(Outcome.Ok(Unit))
        val intent = started()!!
        assertThat(intent.action).isEqualTo(Intent.ACTION_DIAL)
        assertThat(intent.data).isEqualTo(Uri.fromParts("tel", "+1 555 0100", null))
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    /** T-SEARCH-26: CALL with `CALL_PHONE` → `ACTION_CALL`; without it → `ACTION_DIAL`, no SecurityException. */
    @Test
    fun tSearch26_callNeedsThePermission() {
        val source = AndroidContactsSource(app, Dispatchers.IO, Messages())
        shadowOf(app).denyPermissions(Manifest.permission.CALL_PHONE)
        assertThat(source.callPermission()).isEqualTo(PermissionState.DENIED)
        assertThat(source.call("+1 555 0100", CallMode.CALL)).isEqualTo(Outcome.Ok(Unit))
        assertThat(started()!!.action).isEqualTo(Intent.ACTION_DIAL)
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        assertThat(source.call("+1 555 0100", CallMode.CALL)).isEqualTo(Outcome.Ok(Unit))
        val intent = started()!!
        assertThat(intent.action).isEqualTo(Intent.ACTION_CALL)
        assertThat(intent.data).isEqualTo(Uri.fromParts("tel", "+1 555 0100", null))
    }

    /**
     * T-SEARCH-26 (review fix): with CALL and CALL_PHONE, a stored MMI/USSD code or a number with pauses never goes to
     * `ACTION_CALL` (a code like call forwarding would run unseen); it opens the dialer. DIAL keeps every dialable form.
     */
    @Test
    fun tSearch26_codesAndPausesNeverCallDirectly() {
        val source = AndroidContactsSource(app, Dispatchers.IO, Messages())
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        val codes =
            listOf("*21*+15550100#", "#31#5550100", "+1 555 0100,1234", "+1 555 0100;99", "5550100p1", "5550100W2")
        for (code in codes) {
            assertThat(source.call(code, CallMode.CALL)).isEqualTo(Outcome.Ok(Unit))
            val intent = started()!!
            assertThat(intent.action).isEqualTo(Intent.ACTION_DIAL)
            assertThat(intent.data).isEqualTo(Uri.fromParts("tel", code, null))
            assertThat(source.call(code, CallMode.DIAL)).isEqualTo(Outcome.Ok(Unit))
            assertThat(started()!!.action).isEqualTo(Intent.ACTION_DIAL)
        }
        assertThat(source.call("+1 (555) 010-0", CallMode.CALL)).isEqualTo(Outcome.Ok(Unit))
        assertThat(started()!!.action).isEqualTo(Intent.ACTION_CALL)
    }

    /** T-SEARCH-26: anything but dialable characters (or no digit) is refused and nothing starts. */
    @Test
    fun tSearch26_undialableNumbersAreRefused() {
        val source = AndroidContactsSource(app, Dispatchers.IO, Messages())
        for (bad in listOf("", "call me", "555 0100\nextra", "javascript:1", "+", "1".repeat(65))) {
            assertThat(source.call(bad, CallMode.DIAL)).isEqualTo(Outcome.Err(LaunchError.SECURITY))
        }
        assertThat(started()).isNull()
    }

    /** T-SEARCH-26: numbers by contact id (from the lookup URI), default first, duplicate and bad ones dropped. */
    @Test
    fun tSearch26_numbersDefaultFirstDeduplicated() {
        val provider = Robolectric.setupContentProvider(PhoneProvider::class.java, ContactsContract.AUTHORITY)
        provider.rows =
            listOf(
                arrayOf("+1 555 0101", mobile, null, 1, 1),
                arrayOf("+1-555-0101", mobile, null, 0, 0),
                arrayOf("call me", work, null, 0, 0),
                arrayOf("+1 555 0102", work, null, 0, 0),
            )
        val source = AndroidContactsSource(app, Dispatchers.IO, Messages())
        val numbers = runBlocking { source.numbers(zed) }
        assertThat(numbers.map { it.number }).containsExactly("+1 555 0101", "+1 555 0102").inOrder()
        assertThat(numbers.first()).isEqualTo(PhoneNumber("+1 555 0101", numbers.first().label, primary = true))
        assertThat(numbers.map { it.label }).doesNotContain(null)
        assertThat(provider.phoneSelectionArgs).containsExactly("7")
        assertThat(provider.phoneSort).startsWith(ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY + " DESC")
    }

    /** T-SEARCH-26: no `READ_CONTACTS` or a URI that is not a lookup URI → no query at all; a failure logs no number. */
    @Test
    fun tSearch26_numbersGuarded() {
        val provider = Robolectric.setupContentProvider(PhoneProvider::class.java, ContactsContract.AUTHORITY)
        val logger = Messages()
        val source = AndroidContactsSource(app, Dispatchers.IO, logger)
        assertThat(runBlocking { source.numbers(ContactHit("content://evil.provider/contacts/lookup/a/7", "X")) })
            .isEmpty()
        provider.failWith = IllegalStateException("+1 555 0109")
        assertThat(runBlocking { source.numbers(zed) }).isEmpty()
        assertThat(logger.messages.joinToString()).doesNotContain("555")
        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
        provider.failWith = null
        assertThat(runBlocking { source.numbers(zed) }).isEmpty()
        assertThat(provider.phoneSelectionArgs).isNull()
    }
}
