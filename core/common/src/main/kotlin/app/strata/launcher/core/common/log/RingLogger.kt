package app.strata.launcher.core.common.log

import app.strata.launcher.core.common.Clock

/**
 * [Logger] that keeps the newest [capacity] entries in memory (ARCHITECTURE §11: 2 000) and forwards
 * every entry at or above [minLevel] to [sinks] (file, logcat). Thread-safe.
 */
class RingLogger(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: Clock = Clock.System,
    private val sinks: List<LogSink> = emptyList(),
    private val minLevel: LogLevel = LogLevel.DEBUG,
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
        val entry = LogEntry(clock.nowMillis(), level, tag, message, throwable)
        synchronized(lock) {
            buffer[next] = entry
            next = (next + 1) % capacity
            if (size < capacity) size++
        }
        for (sink in sinks) {
            runCatching { sink.write(entry) }
        }
    }

    /** Entries currently held, oldest first. */
    fun snapshot(): List<LogEntry> = synchronized(lock) {
        val start = (next - size + capacity) % capacity
        List(size) { i -> checkNotNull(buffer[(start + i) % capacity]) }
    }

    companion object {
        const val DEFAULT_CAPACITY = 2_000
    }
}
