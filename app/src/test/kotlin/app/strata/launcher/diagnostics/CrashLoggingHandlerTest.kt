package app.strata.launcher.diagnostics

import app.strata.launcher.core.common.log.RingLogger
import app.strata.launcher.core.common.log.RotatingFileLogSink
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class CrashLoggingHandlerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun crashEntryIsOnDiskWhenPreviousHandlerRuns() {
        val dir = tmp.root
        val logFile = File(dir, RotatingFileLogSink.fileName(0))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val slow = Executor { task -> pool.execute { Thread.sleep(200).also { task.run() } } }
            val logger = RingLogger(sinks = listOf(RotatingFileLogSink(directory = { dir }, executor = slow)))
            var onDisk = ""
            // The real platform handler kills the process here: the entry must already be persisted.
            val previous = Thread.UncaughtExceptionHandler { _, _ -> onDisk = logFile.readTextOrEmpty() }
            val handler = CrashLoggingHandler({ logger }, previous)
            handler.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
            assertThat(onDisk).contains("Uncaught exception")
            assertThat(onDisk).contains("boom")
        } finally {
            pool.shutdownNow()
        }
    }

    private fun File.readTextOrEmpty(): String = if (exists()) readText() else ""
}
