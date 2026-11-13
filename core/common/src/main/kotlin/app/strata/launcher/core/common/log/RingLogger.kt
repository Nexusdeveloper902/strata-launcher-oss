package app.strata.launcher.core.common.log

import app.strata.launcher.core.common.Clock

/**
 * [Logger] that keeps the newest [capacity] entries in memory (ARCHITECTURE §11: 2 000) and forwards
 * every entry at or above [minLevel] to [sinks] (file, logcat). Thread-safe. With [redact] (release builds,
 * ARCHITECTURE §11) messages pass through [LogRedaction.message] and throwables lose their messages before any sink or
 * the ring sees them, so package names appear only as hashes.
 */
class RingLogger(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: Clock = Clock.System,
    private val sinks: List<LogSink> = emptyList(),
    private val minLevel: LogLevel = LogLevel.DEBUG,
    private val redact: Boolean = false,
) : Logger {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val buffer = arrayOfNulls<LogEntry>(capacity)
    private var next = 0
    private var size = 0
    private val lock = Any()

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (level < minLevel) return
        val entry =
            if (redact) {
                LogEntry(
                    clock.nowMillis(),
                    level,
                    tag,
                    LogRedaction.message(message),
                    throwable?.let(LogRedaction::throwable),
                )
            } else {
                LogEntry(clock.nowMillis(), level, tag, message, throwable)
            }
        synchronized(lock) {
            buffer[next] = entry
            next = (next + 1) % capacity
            if (size < capacity) size++
        }
        for (sink in sinks) {
            runCatching { sink.write(entry) }
        }
    }

    override fun flush(timeoutMillis: Long) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        for (sink in sinks) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            runCatching { sink.flush(left.coerceAtLeast(0)) }
        }
    }

    /** Entries currently held, oldest first. */
    fun snapshot(): List<LogEntry> =
        synchronized(lock) {
            val start = (next - size + capacity) % capacity
            List(size) { i -> checkNotNull(buffer[(start + i) % capacity]) }
        }

    companion object {
        const val DEFAULT_CAPACITY = 2_000
    }
}
