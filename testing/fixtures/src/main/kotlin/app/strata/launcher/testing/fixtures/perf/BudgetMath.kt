package app.strata.launcher.testing.fixtures.perf

import kotlin.math.ceil

/**
 * Statistics behind the device budget gate (`:benchmark` BudgetGate). Pure, so the arithmetic is unit-tested on the JVM;
 * the budgets themselves are checked only on a physical device.
 */
object BudgetMath {
    /** Nearest-rank percentile, [p] in (0, 100]. The median of an even count is the lower middle value. */
    fun percentile(
        values: List<Double>,
        p: Double,
    ): Double {
        require(values.isNotEmpty()) { "no samples" }
        require(p > 0 && p <= 100) { "p must be in (0, 100]" }
        val sorted = values.sorted()
        val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun median(values: List<Double>): Double = percentile(values, 50.0)

    /**
     * Fraction of janky frames: a frame is janky when it missed its deadline (`frameOverrunMs` > 0), the definition
     * FrameTimingMetric reports on API 31+.
     */
    fun jankFraction(overrunsMs: List<Double>): Double {
        require(overrunsMs.isNotEmpty()) { "no frames" }
        return overrunsMs.count { it > 0.0 }.toDouble() / overrunsMs.size
    }

    /**
     * Frames longer than [limitMs] ("no frame > 50 ms", NFR-PERF-02). A frame's length is its CPU time or, when it
     * overran, its deadline plus the overrun ([frameBudgetMs] = 1000 / refresh rate), whichever is larger.
     */
    fun framesLongerThan(
        cpuMs: List<Double>,
        overrunMs: List<Double>,
        frameBudgetMs: Double,
        limitMs: Double,
    ): Int {
        var n = 0
        for (i in cpuMs.indices) {
            val overrun = overrunMs.getOrElse(i) { 0.0 }
            val length = maxOf(cpuMs[i], if (overrun > 0) frameBudgetMs + overrun else 0.0)
            if (length > limitMs) n++
        }
        return n
    }
}
