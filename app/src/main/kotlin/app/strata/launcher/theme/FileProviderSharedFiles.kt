package app.strata.launcher.theme

import android.content.Context
import androidx.core.content.FileProvider
import app.strata.launcher.core.common.Clock
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.platformapi.system.SharedFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * [SharedFiles] over `cacheDir/share/` and the app's non-exported `FileProvider` (`${applicationId}.files`,
 * ANDROID_PLATFORM §22; `res/xml/file_paths.xml` exposes only `share/`). Each copy gets its own random directory, so the
 * receiver sees the suggested file name and two shares never collide. Copies older than 24 h are deleted by
 * [purgeExpired] at process start (THEME_SYSTEM §10.1).
 */
class FileProviderSharedFiles(
    context: Context,
    private val io: CoroutineDispatcher,
    private val clock: Clock,
    private val logger: Logger,
    private val shareDir: () -> File = { File(context.applicationContext.cacheDir, DIR) },
) : SharedFiles {
    private val appContext = context.applicationContext
    private val authority = appContext.packageName + AUTHORITY_SUFFIX

    override suspend fun publish(
        fileName: String,
        bytes: ByteArray,
    ): String? =
        withContext(io) {
            try {
                val dir = File(shareDir(), UUID.randomUUID().toString())
                if (!dir.mkdirs()) return@withContext null
                val file = File(dir, safeName(fileName))
                file.writeBytes(bytes)
                FileProvider.getUriForFile(appContext, authority, file).toString()
            } catch (e: Exception) {
                logger.w(TAG, "share copy failed", e)
                null
            }
        }

    override suspend fun purgeExpired() {
        withContext(io) {
            val now = clock.nowMillis()
            shareDir().listFiles()?.forEach { entry ->
                val newest = entry.walkTopDown().maxOfOrNull { it.lastModified() } ?: 0L
                if (now - newest >= MAX_AGE_MS) entry.deleteRecursively()
            }
        }
    }

    private fun safeName(name: String): String =
        name
            .map { if (it.isISOControl() || it in "/\\") '_' else it }
            .joinToString("")
            .trimStart('.')
            .ifEmpty { "file" }

    companion object {
        private const val TAG = "SharedFiles"
        const val DIR = "share"
        const val AUTHORITY_SUFFIX = ".files"

        /** §10.1: temporary copies are deleted after 24 h. */
        const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}
