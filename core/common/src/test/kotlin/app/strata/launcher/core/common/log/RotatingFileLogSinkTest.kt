package app.strata.launcher.core.common.log

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RotatingFileLogSinkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun entry(msg: String) = LogEntry(0L, LogLevel.INFO, "t", msg)

    @Test
    fun writesLazilyCreatedDirectory() {
        val dir = File(tmp.root, "logs")
        val sink = RotatingFileLogSink(directory = { dir }, executor = { it.run() })
        sink.write(entry("hello"))
        val text = File(dir, RotatingFileLogSink.fileName(0)).readText()
        assertThat(text).isEqualTo("1970-01-01T00:00:00.000Z I/t: hello\n")
    }

    @Test
    fun rotatesAndKeepsAtMostFileCountFiles() {
        val dir = tmp.root
        val sink = RotatingFileLogSink(directory = { dir }, maxBytes = 100, fileCount = 2, executor = { it.run() })
        repeat(20) { sink.write(entry("message number $it")) }
        val names = dir.list()!!.sorted()
        assertThat(names).containsExactly("strata-0.log", "strata-1.log")
        dir.listFiles()!!.forEach { assertThat(it.length()).isAtMost(100L) }
        assertThat(File(dir, "strata-0.log").readText()).contains("message number 19")
    }
}
