package app.strata.launcher.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

/**
 * Every completed slice named [section] in the target process, as one sampled metric `<section>DurMs` (milliseconds).
 * TraceSectionMetric only reports one aggregate per iteration; the percentile budgets (T-PERF-05 p95, T-PERF-06 p95,
 * T-THEME-44) need each slice.
 */
@OptIn(ExperimentalMetricApi::class)
internal class SliceDurationsMetric(
    private val section: String,
) : TraceMetric() {
    override fun getMeasurements(
        captureInfo: CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Measurement> {
        val ms =
            traceSession
                .querySlices(section, packageName = captureInfo.targetPackageName)
                .filter { it.dur > 0 }
                .map { it.dur / NANOS_PER_MS }
        return if (ms.isEmpty()) emptyList() else listOf(Measurement(name(section), ms))
    }

    companion object {
        fun name(section: String) = "${section}DurMs"

        private const val NANOS_PER_MS = 1_000_000.0
    }
}

/**
 * T-PERF-10: time from the first [from] slice to the first [to] slice that starts after it, as `<label>Ms`
 * (`strata.onPackageAdded` to `strata.rowComposed`). Nothing is reported when either slice is missing.
 */
@OptIn(ExperimentalMetricApi::class)
internal class SliceGapMetric(
    private val from: String,
    private val to: String,
    private val label: String,
) : TraceMetric() {
    override fun getMeasurements(
        captureInfo: CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Measurement> {
        val pkg = captureInfo.targetPackageName
        val start = traceSession.querySlices(from, packageName = pkg).minByOrNull { it.ts } ?: return emptyList()
        val end =
            traceSession
                .querySlices(to, packageName = pkg)
                .filter { it.ts >= start.ts }
                .minByOrNull { it.ts } ?: return emptyList()
        return listOf(Measurement("${label}Ms", (end.ts - start.ts) / 1_000_000.0))
    }
}

/**
 * T-PERF-02 warm return, `warmReturnMs`: from the start of the system's `launching: <target>` slice to the end of the
 * first frame the target's RenderThread draws after it (`DrawFrame…`), i.e. until the returned window is on screen.
 *
 * Replaces `StartupTimingMetric` for this test only: its query pairs the FIRST UI-thread frame after the launch with
 * a RenderThread slice and throws when that frame drew nothing ("No RT frame slice associated with UI thread frame
 * slice after launching slice": runs 43625970551, 44068284763, 44129238421, 44129245277, 44131037055 on main, and
 * PR #19, even with the target verified STOPPED before the launch). On the emulator a warm return's first doFrame can
 * be such an empty frame. A missing launch or a launch that never draws fails the test with its own message; a slow one
 * fails the device budget.
 */
@OptIn(ExperimentalMetricApi::class)
internal class WarmReturnMetric : TraceMetric() {
    override fun getMeasurements(
        captureInfo: CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Measurement> {
        val pkg = captureInfo.targetPackageName
        val launch =
            traceSession
                .query("SELECT ts FROM slice WHERE name = 'launching: $pkg' ORDER BY ts LIMIT 1")
                .firstOrNull()
                ?.long("ts")
                ?: error("T-PERF-02: no 'launching: $pkg' slice in the trace (the Home intent did not launch)")
        val drawn =
            traceSession
                .query(
                    """
                    SELECT slice.ts + slice.dur AS end_ts FROM slice
                    JOIN thread_track ON slice.track_id = thread_track.id
                    JOIN thread USING (utid)
                    JOIN process USING (upid)
                    WHERE (process.name = '$pkg' OR process.name LIKE '$pkg:%')
                      AND thread.name = 'RenderThread'
                      AND slice.name LIKE 'DrawFrame%'
                      AND slice.ts >= $launch AND slice.dur > 0
                    ORDER BY slice.ts LIMIT 1
                    """.trimIndent(),
                ).firstOrNull()
                ?.long("end_ts") ?: error("T-PERF-02: $pkg drew no frame after the warm return's launch")
        return listOf(Measurement(NAME, (drawn - launch) / 1_000_000.0))
    }

    companion object {
        const val NAME = "warmReturnMs"
    }
}
