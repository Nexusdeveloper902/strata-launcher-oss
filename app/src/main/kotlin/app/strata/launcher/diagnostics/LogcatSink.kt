package app.strata.launcher.diagnostics

import android.util.Log
import app.strata.launcher.core.common.log.LogEntry
import app.strata.launcher.core.common.log.LogLevel
import app.strata.launcher.core.common.log.LogSink

/** Mirrors log entries to logcat: everything in debug, WARN+ in release (ARCHITECTURE §11). */
class LogcatSink(private val minLevel: LogLevel) : LogSink {
    override fun write(entry: LogEntry) {
        if (entry.level < minLevel) return
        val tag = "Strata/${entry.tag}"
        when (entry.level) {
            LogLevel.DEBUG -> Log.d(tag, entry.message, entry.throwable)
            LogLevel.INFO -> Log.i(tag, entry.message, entry.throwable)
            LogLevel.WARN -> Log.w(tag, entry.message, entry.throwable)
            LogLevel.ERROR -> Log.e(tag, entry.message, entry.throwable)
        }
    }
}
