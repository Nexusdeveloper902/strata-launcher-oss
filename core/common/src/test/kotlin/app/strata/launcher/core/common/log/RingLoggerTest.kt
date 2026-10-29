package app.strata.launcher.core.common.log

import app.strata.launcher.core.common.Clock
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Logger ring buffer (ARCHITECTURE §11; infrastructure for NFR-REL-01). */
class RingLoggerTest {
    private var now = 0L
    private val clock = Clock { now++ }

    @Test
    fun keepsEntriesInOrderBelowCapacity() {
        val logger = RingLogger(capacity = 3, clock = clock)
        logger.i("t", "a")
        logger.w("t", "b")
        assertThat(logger.snapshot().map { it.message }).containsExactly("a", "b").inOrder()
        assertThat(logger.snapshot().map { it.level }).containsExactly(LogLevel.INFO, LogLevel.WARN).inOrder()
    }

    @Test
    fun dropsOldestWhenFull() {
        val logger = RingLogger(capacity = 3, clock = clock)
        listOf("1", "2", "3", "4", "5").forEach { logger.d("t", it) }
        assertThat(logger.snapshot().map { it.message }).containsExactly("3", "4", "5").inOrder()
    }

    @Test
    fun defaultCapacityIs2000() {
        val logger = RingLogger(clock = clock)
        repeat(2_500) { logger.d("t", it.toString()) }
        val entries = logger.snapshot()
        assertThat(entries).hasSize(2_000)
        assertThat(entries.first().message).isEqualTo("500")
        assertThat(entries.last().message).isEqualTo("2499")
    }

    @Test
    fun forwardsToSinksAndFiltersByMinLevel() {
        val seen = mutableListOf<LogEntry>()
        val logger = RingLogger(capacity = 10, clock = clock, sinks = listOf(LogSink { seen += it }), minLevel = LogLevel.WARN)
        logger.d("t", "debug")
        logger.e("t", "error")
        assertThat(seen.map { it.message }).containsExactly("error")
        assertThat(logger.snapshot().map { it.message }).containsExactly("error")
    }

    @Test
    fun failingSinkDoesNotBreakLogging() {
        val logger = RingLogger(capacity = 2, clock = clock, sinks = listOf(LogSink { error("boom") }))
        logger.i("t", "still logged")
        assertThat(logger.snapshot()).hasSize(1)
    }
}
