package app.strata.launcher.core.common.log

/** Severity, ordered from least to most severe. */
enum class LogLevel(val letter: Char) { DEBUG('D'), INFO('I'), WARN('W'), ERROR('E') }

/** One log record. Never carries notification content, contacts, queries or custom labels (ARCHITECTURE §11). */
data class LogEntry(
    val timeMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
)

/** Local-only logging (D-007): there is no remote sink. */
interface Logger {
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null)

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)

    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)

    fun w(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.ERROR, tag, message, throwable)
}

/** A destination for entries accepted by [RingLogger]. Implementations must not block the caller. */
fun interface LogSink {
    fun write(entry: LogEntry)
}
