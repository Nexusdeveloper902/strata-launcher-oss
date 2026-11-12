package app.strata.launcher.backup

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.testing.fixtures.BackupFixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/**
 * T-BACKUP-12: 10 000 mutated backup files from the valid fixtures. 0 crashes; every result is a typed rejection or a
 * decoded document; ≤ 200 ms per file (the TEST_PLAN budget).
 */
class BackupFuzzTest {
    private val seeds =
        listOf(
            BackupFixtures.validBytes(),
            BackupFixtures.newerMinor(),
            BackupFixtures.mixedThemes(),
            BackupFixtures.newerTheme(),
        )

    private val tokens =
        listOf(
            "{",
            "}",
            "[",
            "]",
            ",",
            ":",
            "\"",
            "null",
            "true",
            "-1",
            "1e999",
            "\"private\"",
            "\"work\"",
            "\"../x\"",
            "\\u0000",
            "\uD800",
            "{\"format\":\"strata-backup\"}",
            "9".repeat(40),
        ).map { it.toByteArray() }

    private fun mutate(
        src: ByteArray,
        r: Random,
    ): ByteArray {
        var b = src
        repeat(1 + r.nextInt(4)) {
            b =
                when (r.nextInt(6)) {
                    0 -> {
                        b.copyOf().also { if (it.isNotEmpty()) it[r.nextInt(it.size)] = r.nextInt(256).toByte() }
                    }

                    1 -> {
                        if (b.isEmpty()) b else b.copyOfRange(0, r.nextInt(b.size))
                    }

                    2 -> {
                        val i = r.nextInt(b.size + 1)
                        b.copyOfRange(0, i) + tokens[r.nextInt(tokens.size)] + b.copyOfRange(i, b.size)
                    }

                    3 -> {
                        if (b.size < 2) {
                            b
                        } else {
                            val i = r.nextInt(b.size - 1)
                            val n = 1 + r.nextInt(minOf(64, b.size - i))
                            b.copyOfRange(0, i) + b.copyOfRange(i + n, b.size)
                        }
                    }

                    4 -> {
                        // Duplicate a slice (nesting, repeated keys).
                        if (b.isEmpty()) {
                            b
                        } else {
                            val i = r.nextInt(b.size)
                            val n = 1 + r.nextInt(minOf(512, b.size - i))
                            b.copyOfRange(0, i + n) + b.copyOfRange(i, b.size)
                        }
                    }

                    else -> {
                        String(
                            b,
                            Charsets.UTF_8,
                        ).replace("main", if (r.nextBoolean()) "work" else "private").toByteArray()
                    }
                }
        }
        return b
    }

    @Test
    fun tBackup12_tenThousandMutations_neverCrash() {
        val r = Random(12)
        var ok = 0
        var rejected = 0
        var slowest = 0L
        repeat(10_000) { i ->
            val input = mutate(seeds[i % seeds.size], r)
            val start = System.nanoTime()
            when (BackupCodec.decode(input)) {
                is Outcome.Ok -> ok++
                is Outcome.Err -> rejected++
            }
            slowest = maxOf(slowest, (System.nanoTime() - start) / 1_000_000)
        }
        assertThat(ok + rejected).isEqualTo(10_000)
        assertThat(ok).isGreaterThan(0)
        assertThat(rejected).isGreaterThan(0)
        assertThat(slowest).isAtMost(200)
    }
}
