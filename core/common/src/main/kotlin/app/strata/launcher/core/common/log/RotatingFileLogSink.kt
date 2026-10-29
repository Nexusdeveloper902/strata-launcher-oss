package app.strata.launcher.core.common.log

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Appends entries to `strata-0.log` in [directory()] and rotates to `strata-1.log` when the
 * current file would exceed [maxBytes] (ARCHITECTURE §11: 2 × 256 KiB). [directory] is resolved
 * lazily on [executor], so no disk access happens on the calling thread (StrictMode, NFR-REL-01).
 */
class RotatingFileLogSink(
    private val directory: () -> File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val fileCount: Int = DEFAULT_FILE_COUNT,
    private val executor: Executor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "strata-log").apply { isDaemon = true }
        },
) : LogSink {
    init {
        require(fileCount >= 1) { "fileCount must be at least 1" }
    }

    override fun write(entry: LogEntry) {
        val line = format(entry)
        executor.execute { runCatching { append(line) } }
    }

    private fun append(line: String) {
        val dir = directory()
        if (!dir.isDirectory) dir.mkdirs()
        val current = File(dir, fileName(0))
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (current.exists() && current.length() + bytes.size > maxBytes) rotate(dir)
        current.appendBytes(bytes)
    }

    private fun rotate(dir: File) {
        File(dir, fileName(fileCount - 1)).delete()
        for (i in fileCount - 2 downTo 0) {
            val from = File(dir, fileName(i))
            if (from.exists()) from.renameTo(File(dir, fileName(i + 1)))
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 256L * 1024
        const val DEFAULT_FILE_COUNT = 2

        fun fileName(index: Int): String = "strata-$index.log"

        internal fun format(entry: LogEntry): String {
            val time =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .format(Date(entry.timeMillis))
            val sb =
                StringBuilder()
                    .append(time)
                    .append(' ')
                    .append(entry.level.letter)
                    .append('/')
                    .append(entry.tag)
                    .append(": ")
                    .append(entry.message)
                    .append('\n')
            entry.throwable?.let { t ->
                val sw = StringWriter()
                t.printStackTrace(PrintWriter(sw))
                sb.append(sw.toString())
            }
            return sb.toString()
        }
    }
}
