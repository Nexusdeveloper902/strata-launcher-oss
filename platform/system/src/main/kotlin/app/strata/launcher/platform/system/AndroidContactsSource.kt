package app.strata.launcher.platform.system

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.Trace
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.IconImage
import app.strata.launcher.core.platformapi.apps.LaunchError
import app.strata.launcher.core.platformapi.apps.LaunchOrigin
import app.strata.launcher.core.platformapi.apps.LaunchResult
import app.strata.launcher.core.platformapi.system.ContactHit
import app.strata.launcher.core.platformapi.system.ContactsSource
import app.strata.launcher.core.platformapi.system.PermissionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Trace section around one contact query (T-PERF-06 on device). */
const val TRACE_CONTACTS_QUERY = "strata.contactsQuery"

/**
 * [ContactsSource] over `ContactsContract.Contacts.CONTENT_FILTER_URI` with `LIMIT` (ARCHITECTURE §8). Runs on [io]
 * and is cancelled with the calling coroutine (a `CancellationSignal`). Reads only the display name, the lookup key
 * and the thumbnail; nothing is cached, stored or logged (ARCHITECTURE §11: contact data is never logged).
 */
class AndroidContactsSource(
    context: Context,
    private val io: CoroutineDispatcher,
    private val logger: Logger,
) : ContactsSource {
    private val appContext = context.applicationContext

    override fun permission(): PermissionState =
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            PermissionState.GRANTED
        } else {
            PermissionState.DENIED
        }

    override suspend fun search(
        query: String,
        limit: Int,
    ): List<ContactHit> {
        val q = query.trim()
        if (q.isEmpty() || limit <= 0 || permission() != PermissionState.GRANTED) return emptyList()
        return withContext(io) {
            val signal = CancellationSignal()
            val job = currentCoroutineContext()[Job]
            val handle = job?.invokeOnCompletion { signal.cancel() }
            Trace.beginSection(TRACE_CONTACTS_QUERY)
            try {
                val hits = query(q, limit, signal)
                hits.map { (hit, thumb) ->
                    ensureActive()
                    if (thumb == null) hit else hit.copy(photo = decodeThumbnail(thumb))
                }
            } catch (e: OperationCanceledException) {
                ensureActive()
                emptyList()
            } catch (e: SecurityException) {
                emptyList() // revoked between the check and the query
            } catch (e: RuntimeException) {
                logger.w(TAG, "contact query failed: ${e.javaClass.simpleName}")
                emptyList()
            } finally {
                Trace.endSection()
                handle?.dispose()
            }
        }
    }

    /** Hits with their thumbnail URI (decoded afterwards, outside the cursor). */
    private fun query(
        q: String,
        limit: Int,
        signal: CancellationSignal,
    ): List<Pair<ContactHit, String?>> {
        val uri =
            ContactsContract.Contacts.CONTENT_FILTER_URI
                .buildUpon()
                .appendPath(q)
                .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, limit.toString())
                .build()
        val out = ArrayList<Pair<ContactHit, String?>>(limit)
        appContext.contentResolver.query(uri, PROJECTION, null, null, null, signal)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val lookupCol = c.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
            val nameCol = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val photoCol = c.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
            while (c.moveToNext() && out.size < limit) {
                val name = c.getString(nameCol)?.trim().orEmpty()
                val lookup = c.getString(lookupCol) ?: continue
                if (name.isEmpty()) continue
                val lookupUri = ContactsContract.Contacts.getLookupUri(c.getLong(idCol), lookup) ?: continue
                out += ContactHit(lookupUri = lookupUri.toString(), name = name) to c.getString(photoCol)
            }
        }
        return out
    }

    private fun decodeThumbnail(uri: String): IconImage? =
        try {
            appContext.contentResolver.openInputStream(uri.toUri())?.use { input ->
                BitmapFactory.decodeStream(input)?.let {
                    IconImage(it, it.width, it.height)
                }
            }
        } catch (e: java.io.IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }

    override fun open(
        hit: ContactHit,
        origin: LaunchOrigin?,
    ): LaunchResult {
        val uri = hit.lookupUri.toUri()
        // Only a contacts lookup URI is ever opened from a result.
        if (uri.authority != ContactsContract.AUTHORITY) return Outcome.Err(LaunchError.SECURITY)
        val intent =
            Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        origin?.let { intent.sourceBounds = Rect(it.left, it.top, it.right, it.bottom) }
        return try {
            appContext.startActivity(intent)
            Outcome.Ok(Unit)
        } catch (e: ActivityNotFoundException) {
            Outcome.Err(LaunchError.NOT_FOUND)
        } catch (e: SecurityException) {
            Outcome.Err(LaunchError.SECURITY)
        }
    }

    private companion object {
        const val TAG = "Contacts"
        val PROJECTION =
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            )
    }
}
