package app.strata.launcher.diagnostics

import app.strata.launcher.core.common.log.Logger

/**
 * Process-wide uncaught-exception hook: records the crash in the local log (no upload, D-007)
 * and then hands over to the previous handler so the platform still reports the crash.
 */
class CrashLoggingHandler(
    private val logger: () -> Logger,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching { logger().e(TAG, "Uncaught exception on thread ${thread.name}", throwable) }
        // The file sink appends asynchronously and the platform handler kills the process: drain first.
        runCatching { logger().flush(FLUSH_TIMEOUT_MILLIS) }
        previous?.uncaughtException(thread, throwable)
    }

    companion object {
        private const val TAG = "Crash"
        private const val FLUSH_TIMEOUT_MILLIS = 1_500L

        fun install(logger: () -> Logger) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous is CrashLoggingHandler) return
            Thread.setDefaultUncaughtExceptionHandler(CrashLoggingHandler(logger, previous))
        }
    }
}
