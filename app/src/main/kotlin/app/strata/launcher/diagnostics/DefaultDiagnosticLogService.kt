package app.strata.launcher.diagnostics

import android.os.Build
import app.strata.launcher.core.common.log.DiagnosticLog
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.platformapi.system.DiagnosticLogService
import app.strata.launcher.core.platformapi.system.DocumentIO
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [DiagnosticLogService] over the rotated files of `noBackupFilesDir/logs/` ([DiagnosticLog]). The header names the
 * version, build type and API level only: no device serial, install id or account.
 */
class DefaultDiagnosticLogService(
    private val directory: () -> File,
    private val logger: Logger,
    private val documents: DocumentIO,
    private val io: CoroutineDispatcher,
    private val versionName: String,
    private val buildType: String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : DiagnosticLogService {
    override suspend fun build(): String =
        withContext(io) {
            logger.flush(FLUSH_MILLIS)
            DiagnosticLog.build(
                directory = directory(),
                header =
                    listOf(
                        "Strata Launcher diagnostic log",
                        "Version $versionName ($buildType), Android API ${Build.VERSION.SDK_INT}",
                        "Created ${stamp("yyyy-MM-dd'T'HH:mm'Z'")}",
                        "Package names appear as <id:xxxxxx> hashes; app names, searches and contacts are never logged.",
                    ),
            )
        }

    override suspend fun save(
        uri: String,
        text: String,
    ): Boolean = documents.write(uri, text.toByteArray(Charsets.UTF_8))

    override fun suggestedFileName(): String = "strata-diagnostic-${stamp("yyyyMMdd-HHmm")}.txt"

    private fun stamp(pattern: String): String =
        SimpleDateFormat(pattern, Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(Date(nowMillis()))

    private companion object {
        const val FLUSH_MILLIS = 1_000L
    }
}
