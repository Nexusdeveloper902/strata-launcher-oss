package app.strata.launcher.core.common.log

import java.io.File

/**
 * The text of About -> "Save diagnostic log" (ARCHITECTURE §11): a short header, then the rotated log files oldest
 * first, every line passed through [LogRedaction.exportLine], whatever build type wrote them (T-PRIV-06). Only the
 * newest [maxChars] characters are kept. Blocking: call on I/O.
 */
object DiagnosticLog {
    const val DEFAULT_MAX_CHARS = 512 * 1024

    fun build(
        directory: File,
        header: List<String>,
        maxChars: Int = DEFAULT_MAX_CHARS,
        fileCount: Int = RotatingFileLogSink.DEFAULT_FILE_COUNT,
    ): String {
        val body = StringBuilder()
        for (i in fileCount - 1 downTo 0) {
            val f = File(directory, RotatingFileLogSink.fileName(i))
            val lines =
                try {
                    if (f.isFile) f.readLines(Charsets.UTF_8) else emptyList()
                } catch (e: java.io.IOException) {
                    listOf("<log file ${RotatingFileLogSink.fileName(i)} unreadable: ${e.javaClass.simpleName}>")
                }
            for (line in lines) body.append(LogRedaction.exportLine(line)).append('\n')
        }
        val trimmed = if (body.length > maxChars) body.substring(body.length - maxChars).substringAfter('\n') else body
        val out = StringBuilder()
        for (h in header) out.append(LogRedaction.message(h)).append('\n')
        out.append('\n')
        if (trimmed.isEmpty()) out.append(EMPTY).append('\n') else out.append(trimmed)
        return out.toString()
    }

    const val EMPTY = "(no entries)"
}
