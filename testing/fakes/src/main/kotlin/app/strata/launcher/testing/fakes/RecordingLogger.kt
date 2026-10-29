package app.strata.launcher.testing.fakes

import app.strata.launcher.core.common.log.LogEntry
import app.strata.launcher.core.common.log.LogLevel
import app.strata.launcher.core.common.log.Logger
import java.util.concurrent.CopyOnWriteArrayList

/** [Logger] that keeps every entry for assertions. */
class RecordingLogger : Logger {
    val entries: MutableList<LogEntry> = CopyOnWriteArrayList()

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        entries += LogEntry(0L, level, tag, message, throwable)
    }
}
