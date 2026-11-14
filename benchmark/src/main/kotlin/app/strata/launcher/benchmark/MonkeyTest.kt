package app.strata.launcher.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-REL-01 short form (NFR-REL-01) on the release-like `benchmark` build, so R8 output gets random input too. Runs
 * `monkey` restricted to the target package (other packages' activities are refused, so system dialogs such as
 * uninstall or the role request never open) and fails on any crash or ANR it reports. The target lives in its own
 * process here, so a crash is reported instead of taking the test down, and its data is cleared afterwards.
 *
 * Instrumentation arguments: `strata.monkeyEvents` (default 1 000, the CI run; 0 skips), `strata.monkeySeed`
 * (default 4242), `strata.monkeyThrottle` (ms, default 100). The 1 h T-REL-01 run (70 000 events, throttle 50, API 34
 * or the A56) is `tools/reliability/monkey.sh`.
 *
 * Afterwards, T-PRIV-01 on the release-like build: `/proc/net` lists no socket owned by the target's UID.
 */
@RunWith(AndroidJUnit4::class)
class MonkeyTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val args = InstrumentationRegistry.getArguments()

    @After
    fun clearTargetData() {
        // Random input changes settings, favourites and themes; the other benchmarks expect a fresh app.
        device.executeShellCommand("pm clear ${Strata.TARGET}")
    }

    @Test
    fun tRel01_monkeyShort() {
        val events = args.getString("strata.monkeyEvents")?.toInt() ?: DEFAULT_EVENTS
        assumeTrue("strata.monkeyEvents=0", events > 0)
        val seed = args.getString("strata.monkeySeed")?.toLong() ?: DEFAULT_SEED
        val throttle = args.getString("strata.monkeyThrottle")?.toInt() ?: DEFAULT_THROTTLE_MS
        device.executeShellCommand("am force-stop ${Strata.TARGET}")
        device.executeShellCommand(Strata.HOME_START_COMMAND)
        device.passOnboardingAndWaitForHome()

        val out =
            device.executeShellCommand(
                "monkey -p ${Strata.TARGET} -s $seed --throttle $throttle --pct-syskeys 0 " +
                    "--monitor-native-crashes -v $events",
            )
        val verdict = MonkeyVerdict.of(out)
        BudgetGate.log("T-REL-01 short: $events events, seed $seed: $verdict")
        assertTrue("T-REL-01: $verdict\n${out.takeLast(OUTPUT_TAIL)}", verdict.passed)

        val uid = uidOf(Strata.TARGET)
        if (uid != null) {
            val tables =
                listOf(
                    "tcp",
                    "tcp6",
                    "udp",
                    "udp6",
                ).associateWith { device.executeShellCommand("cat /proc/net/$it") }
            val owned =
                tables.flatMap { (t, text) ->
                    text
                        .lines()
                        .drop(1)
                        .filter { socketUid(it) == uid }
                        .map { "$t: $it" }
                }
            assertTrue("T-PRIV-01: sockets owned by the target UID $uid:\n${owned.joinToString("\n")}", owned.isEmpty())
        }
    }

    private fun uidOf(pkg: String): Int? =
        Regex("""uid:(\d+)""")
            .find(device.executeShellCommand("cmd package list packages -U $pkg"))
            ?.groupValues
            ?.get(1)
            ?.toInt()

    private fun socketUid(row: String): Int? =
        row
            .trim()
            .split(Regex("\\s+"))
            .getOrNull(7)
            ?.toIntOrNull()

    private companion object {
        const val DEFAULT_EVENTS = 1_000
        const val DEFAULT_SEED = 4242L
        const val DEFAULT_THROTTLE_MS = 100
        const val OUTPUT_TAIL = 4_000
    }
}

/** What a `monkey -v` run reported (pure; the same rules as tools/reliability/monkey.sh). */
internal data class MonkeyVerdict(
    val finished: Boolean,
    val crashes: Int,
    val anrs: Int,
) {
    val passed: Boolean get() = finished && crashes == 0 && anrs == 0

    companion object {
        fun of(output: String): MonkeyVerdict =
            MonkeyVerdict(
                finished = "// Monkey finished" in output,
                crashes =
                    Regex("""// CRASH:""").findAll(output).count() +
                        Regex("""// NATIVE CRASH""").findAll(output).count(),
                anrs = Regex("""// NOT RESPONDING:""").findAll(output).count(),
            )
    }
}
