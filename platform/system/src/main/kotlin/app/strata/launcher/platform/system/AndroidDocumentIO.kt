package app.strata.launcher.platform.system

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import app.strata.launcher.core.platformapi.system.DocumentIO
import app.strata.launcher.core.platformapi.system.DocumentRead
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * [DocumentIO] over `ContentResolver` (ANDROID_PLATFORM §16). The scheme is checked before the resolver is touched: a
 * `file:`, `http:` or any other URI is refused without opening anything (ARCHITECTURE §13, T-SEC-01). Reads stop at
 * cap + 1 bytes.
 */
class AndroidDocumentIO(
    context: Context,
    private val io: CoroutineDispatcher,
) : DocumentIO {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun read(
        uri: String,
        maxBytes: Int,
    ): DocumentRead {
        val parsed = contentUri(uri) ?: return DocumentRead.UnsupportedScheme
        return withContext(io) {
            try {
                val stream = resolver.openInputStream(parsed) ?: return@withContext DocumentRead.Failed
                stream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(BUFFER)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) return@withContext DocumentRead.TooLarge
                        out.write(buf, 0, n)
                    }
                    DocumentRead.Ok(out.toByteArray())
                }
            } catch (e: Exception) {
                // FileNotFoundException, SecurityException (revoked grant), IOException: report, keep state.
                DocumentRead.Failed
            }
        }
    }

    override suspend fun write(
        uri: String,
        bytes: ByteArray,
    ): Boolean {
        val parsed = contentUri(uri) ?: return false
        return withContext(io) {
            try {
                val stream = resolver.openOutputStream(parsed, "wt") ?: return@withContext false
                stream.use { it.write(bytes) }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun contentUri(uri: String): Uri? {
        val parsed = Uri.parse(uri)
        return parsed.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT && !it.authority.isNullOrEmpty() }
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
