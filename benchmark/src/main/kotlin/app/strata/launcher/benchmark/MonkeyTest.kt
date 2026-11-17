package app.strata.launcher.benchmark

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * T-REL-01 short form (NFR-REL-01) on the release-like `benchmark` build, so R8 output gets random input too. Runs
 * `monkey` restricted to the target package (other packages' activities are refused, so system dialogs such as
 * uninstall or the role request never open) and fails on any crash or ANR it reports. The target lives in its own
 * process here, so a crash is reported instead of taking the test down, and its data is cleared afterwards.
 *
 * Monkey runs on, past any process's crash or ANR (`--ignore-crashes`, `--ignore-timeouts`); T-REL-01 fails on those of
 * the target and lists the others ([MonkeyVerdict]). The nightly job uses an ATD image (no GMS background load; the
 * Google APIs image ran at load 20 on 2 cores and timed out input to a starting window, nightly run 43994307244).
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

        // --ignore-crashes / --ignore-timeouts: monkey keeps going after any process's crash or ANR instead of aborting
        // (it reports every one, on stderr). The verdict then fails on Strata's own reports and lists the others, so a
        // System UI or GMS ANR on a loaded emulator is visible without ending the run.
        val (out, err) =
            runCapturingStderr(
                "monkey -p ${Strata.TARGET} -s $seed --throttle $throttle --pct-syskeys 0 " +
                    "--ignore-crashes --ignore-timeouts --monitor-native-crashes -v $events",
            )
        val verdict = MonkeyVerdict.of(out, err, Strata.TARGET)
        BudgetGate.log("T-REL-01 short: $events events, seed $seed: $verdict")
        assertTrue(
            "T-REL-01: $verdict\n${anrReport(verdict)}--- monkey stderr (tail)\n${err.takeLast(OUTPUT_TAIL)}\n" +
                "--- monkey stdout (tail)\n${out.takeLast(OUTPUT_TAIL)}",
            verdict.passed,
        )

        // T-PRIV-01 on the release-like build. Both preconditions are explicit: no silent pass.
        val uid = uidOf(Strata.TARGET)
        assertNotNull("T-PRIV-01: no UID for ${Strata.TARGET} from `cmd package list packages -U`", uid)
        val tables =
            listOf(
                "tcp",
                "tcp6",
                "udp",
                "udp6",
            ).associateWith { device.executeShellCommand("cat /proc/net/$it") }
        // The monkey verdict above has already passed; an unreadable /proc/net shows this test as skipped, not passed.
        assumeTrue(
            "T-PRIV-01: the shell cannot read /proc/net here (monkey passed): ${tables.mapValues {
                it.value.take(
                    80,
                )
            }}",
            tables.values.all { "local_address" in it },
        )
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

    /**
     * Runs [command] (a plain exec, no shell) and returns its stdout and stderr. Monkey prints its crash and ANR reports
     * (`// CRASH:`, `// NOT RESPONDING:`) and the reason for an abort on stderr, which `UiDevice.executeShellCommand`
     * drops: with stdout alone the verdict could never count a crash (nightly runs 43599797441, 43699451179 and
     * 43842913826 ended in "Monkey aborted due to error" with crashes=0, anrs=0). API 31+ has a separate stderr pipe.
     */
    private fun runCapturingStderr(command: String): Pair<String, String> {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "T-REL-01 needs API 31+ here to read monkey's stderr (crash and ANR reports); use tools/reliability/monkey.sh"
        }
        val automation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation(Configurator.getInstance().uiAutomationFlags)
        val (stdout, stdin, stderr) = automation.executeShellCommandRwe(command)
        stdin.close()
        val errText = AtomicReference("")
        val errReader =
            thread(name = "monkey-stderr") {
                errText.set(ParcelFileDescriptor.AutoCloseInputStream(stderr).use { it.readBytes().decodeToString() })
            }
        val outText = ParcelFileDescriptor.AutoCloseInputStream(stdout).use { it.readBytes().decodeToString() }
        errReader.join()
        return outText to errText.get()
    }

    /**
     * For an ANR of the target: the system's ANR report from dropbox (subject and the target's main-thread stack), so
     * the failure says whether the app's main thread was blocked or idle (a window that never got focus).
     */
    private fun anrReport(verdict: MonkeyVerdict): String {
        if (verdict.targetAnrs.isEmpty()) return ""
        val dump = device.executeShellCommand("dumpsys dropbox --print data_app_anr")
        val entry =
            dump
                .split(Regex("""(?m)^(?=\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} data_app_anr)"""))
                .lastOrNull { "Process: ${Strata.TARGET}" in it }
                ?: return "--- no data_app_anr entry for ${Strata.TARGET} in dropbox\n"
        val head = entry.lineSequence().takeWhile { it.isNotBlank() }.joinToString("\n")
        val main =
            entry
                .substringAfter("\n\"main\"", missingDelimiterValue = "")
                .lineSequence()
                .takeWhile { it.isNotBlank() }
                .joinToString("\n")
        return "--- ANR report (dropbox)\n${head.take(ANR_HEAD)}\n\"main\"$main\n".take(ANR_REPORT)
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
        const val ANR_HEAD = 1_500
        const val ANR_REPORT = 6_000
    }
}

/**
 * What a `monkey -v` run reported (pure; the same rules as tools/reliability/monkey.sh). [crashes] and [anrs] name the
 * process of every `// CRASH:` / `// NATIVE CRASH` / `// NOT RESPONDING:` report on either stream; only reports for the
 * target's own processes ([target], `target:*`) fail T-REL-01 (NFR-REL-01 is about Strata). Reports for other processes
 * are listed in the message so they stay visible.
 */
internal data class MonkeyVerdict(
    val finished: Boolean,
    val crashes: List<String>,
    val anrs: List<String>,
    val target: String,
    val abortReason: String? = null,
) {
    val targetCrashes: List<String> get() = crashes.filter(::isTarget)
    val targetAnrs: List<String> get() = anrs.filter(::isTarget)
    val passed: Boolean get() = finished && targetCrashes.isEmpty() && targetAnrs.isEmpty()

    private fun isTarget(process: String) = process == target || process.startsWith("$target:")

    companion object {
        private val CRASH = Regex("""// (?:NATIVE )?CRASH:? (\S+)""")
        private val ANR = Regex("""// NOT RESPONDING: (\S+)""")
        private val ERROR = Regex("""^\*\* (?!Monkey aborted)(.+)$""", RegexOption.MULTILINE)

        fun of(
            stdout: String,
            stderr: String,
            target: String,
        ): MonkeyVerdict {
            val all = stdout + "\n" + stderr
            return MonkeyVerdict(
                finished = "// Monkey finished" in stdout,
                crashes = CRASH.findAll(all).map { it.groupValues[1] }.toList(),
                anrs = ANR.findAll(all).map { it.groupValues[1] }.toList(),
                target = target,
                abortReason = ERROR.findAll(all).map { it.groupValues[1].trim() }.firstOrNull(),
            )
        }
    }
}
