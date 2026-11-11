package app.strata.launcher.platform.system

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
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
import app.strata.launcher.core.platformapi.system.ContactHit
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
}
