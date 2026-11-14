package app.strata.launcher.benchmark

import android.os.Build
import android.util.Log
import androidx.benchmark.Outputs
import androidx.test.platform.app.InstrumentationRegistry
import app.strata.launcher.testing.fixtures.perf.BudgetMath
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import java.io.File

/**
 * The M9 blocking budgets (IMPLEMENTATION_ROADMAP M9, [app.strata.launcher.testing.fixtures.perf.PerfBudgets]) checked
 * against the results the benchmark just wrote (`<test package>-benchmarkData.json`, rewritten after every test).
 *
 * Enforced only on a physical device: never on an emulator (CI runs the benchmarks as correctness checks, dry run, with
 * `androidx.benchmark.suppressErrors=EMULATOR`), never in dry-run mode, and not with `-e strata.budgets off` (an
 * exploratory run). Every result is logged under the tag `StrataBudget` either way.
 */
internal object BudgetGate {
    private const val TAG = "StrataBudget"

    private val args get() = InstrumentationRegistry.getArguments()

    val isEmulator: Boolean
        get() =
            Build.HARDWARE in setOf("ranchu", "goldfish", "cutf_cvm", "vbox86") ||
                Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.contains("emulator") ||
                Build.MODEL.contains("sdk_gphone") ||
                Build.PRODUCT.startsWith("sdk")

    private val dryRun: Boolean get() = args.getString("androidx.benchmark.dryRunMode.enable") == "true"

    val enforced: Boolean get() = !isEmulator && !dryRun && args.getString("strata.budgets") != "off"

    /** One test's results: single metrics (one value per iteration) and sampled metrics (all samples). */
    class Result(
        val single: Map<String, List<Double>>,
        val sampled: Map<String, List<Double>>,
        val sampledRuns: Map<String, List<List<Double>>>,
    ) {
        fun values(metric: String): List<Double> =
            single[metric] ?: sampled[metric]
                ?: error("metric '$metric' missing; recorded: ${single.keys + sampled.keys}")
    }

    /** A budget: returns a failure message or null. */
    fun interface Check {
        fun evaluate(r: Result): String?
    }

    fun medianAtMost(
        metric: String,
        limit: Double,
        id: String,
    ) = Check { r ->
        val v = BudgetMath.median(r.values(metric))
        log("$id $metric median=%.2f budget<=%.2f".format(v, limit))
        if (v > limit) "$id: $metric median %.2f > %.2f".format(v, limit) else null
    }

    fun percentileAtMost(
        metric: String,
        p: Double,
        limit: Double,
        id: String,
    ) = Check { r ->
        val v = BudgetMath.percentile(r.values(metric), p)
        log("$id $metric p%.0f=%.2f budget<=%.2f".format(p, v, limit))
        if (v > limit) "$id: $metric p%.0f %.2f > %.2f".format(p, v, limit) else null
    }

    fun maxAtMost(
        metric: String,
        limit: Double,
        id: String,
    ) = Check { r ->
        val v = r.values(metric).max()
        log("$id $metric max=%.2f budget<=%.2f".format(v, limit))
        if (v > limit) "$id: $metric max %.2f > %.2f".format(v, limit) else null
    }

    /** NFR-PERF-02: janky fraction ([BudgetMath.jankFraction]) and no frame longer than [longestMs]. */
    fun frames(
        maxJank: Double,
        longestMs: Double,
        id: String,
    ) = Check { r ->
        val overrun = r.values("frameOverrunMs")
        val cpu = r.values("frameDurationCpuMs")
        val jank = BudgetMath.jankFraction(overrun)
        val long = BudgetMath.framesLongerThan(cpu, overrun, frameBudgetMs(), longestMs)
        log("$id frames=${overrun.size} jank=%.4f budget<=%.4f long(>%.0fms)=$long".format(jank, maxJank, longestMs))
        when {
            jank > maxJank -> "$id: janky frames %.2f %% > %.2f %%".format(jank * 100, maxJank * 100)
            long > 0 -> "$id: $long frame(s) longer than %.0f ms".format(longestMs)
            else -> null
        }
    }

    /**
     * Checks [checks] against [testName]'s results in [testClass]. On an emulator or in a dry run it only logs that
     * the budgets were skipped; the benchmark itself has already proved the journey works.
     */
    fun check(
        testClass: Class<*>,
        testName: String,
        vararg checks: Check,
    ) {
        if (!enforced) {
            log("$testName: budgets not enforced (emulator=$isEmulator, dryRun=$dryRun); journey checked only")
            return
        }
        val result = read(testClass.name, testName)
        val failures = checks.mapNotNull { it.evaluate(result) }
        if (failures.isNotEmpty()) fail("Budget missed on ${Build.MODEL}:\n" + failures.joinToString("\n"))
    }

    fun log(msg: String) {
        Log.i(TAG, msg)
    }

    /** 1000 / refresh rate of the default display (60 Hz on RD-1, 120 Hz on RD-2 for T-PERF-11). */
    fun frameBudgetMs(): Double {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val dm = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
        val hz = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f
        return 1000.0 / hz
    }

    private fun read(
        className: String,
        testName: String,
    ): Result {
        val pkg = InstrumentationRegistry.getInstrumentation().context.packageName
        val file = File(Outputs.outputDirectory, "$pkg-benchmarkData.json")
        if (!file.exists()) fail("benchmark results not found at $file (androidx.benchmark.output.enable must stay on)")
        val benchmarks = JSONObject(file.readText()).getJSONArray("benchmarks")
        var entry: JSONObject? = null
        for (i in 0 until benchmarks.length()) {
            val b = benchmarks.getJSONObject(i)
            if (b.optString("className") == className && b.optString("name") == testName) entry = b
        }
        val e = entry ?: error("no results for $className#$testName in $file")
        val single = HashMap<String, List<Double>>()
        e.optJSONObject("metrics")?.let { m ->
            m.keys().forEach { k -> single[k] = doubles(m.getJSONObject(k).getJSONArray("runs")) }
        }
        val sampled = HashMap<String, List<Double>>()
        val sampledRuns = HashMap<String, List<List<Double>>>()
        e.optJSONObject("sampledMetrics")?.let { m ->
            m.keys().forEach { k ->
                val runs = m.getJSONObject(k).getJSONArray("runs")
                val perRun = (0 until runs.length()).map { doubles(runs.getJSONArray(it)) }
                sampledRuns[k] = perRun
                sampled[k] = perRun.flatten()
            }
        }
        return Result(single, sampled, sampledRuns)
    }

    private fun doubles(a: JSONArray): List<Double> = (0 until a.length()).map { a.getDouble(it) }
}
