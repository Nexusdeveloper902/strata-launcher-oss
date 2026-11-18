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
 * T-PERF-02 warm return, `warmReturnMs`: from the start of the launch ([launchStart]) to the end of the
 * first frame the target's RenderThread draws (`DrawFrame…`) after HomeActivity's `onResume` has run
 * (`strata.home.onResume`, traced by the app in every build). Anchoring on the resume means a frame drawn earlier (a
 * leftover or animation frame of the window before it was brought back) cannot end the measurement: the first frame
 * after `onResume` is the returned window's.
 *
 * Replaces `StartupTimingMetric` for this test only: its query pairs the FIRST UI-thread frame after the launch with
 * a RenderThread slice and throws when that frame drew nothing ("No RT frame slice associated with UI thread frame
 * slice after launching slice": runs 43625970551, 44068284763, 44129238421, 44129245277, 44131037055 on main, and
 * PRs #19 and #20, even with the target verified STOPPED before the launch). Only frames that drew are considered
 * here. A launch without a resume, or a resume that never draws, fails the test with its own message; a slow one
 * fails the device budget.
 */
@OptIn(ExperimentalMetricApi::class)
internal class WarmReturnMetric : TraceMetric() {
    override fun getMeasurements(
        captureInfo: CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Measurement> {
        val pkg = captureInfo.targetPackageName
        val launch = launchStart(traceSession, pkg)
        val resumed =
            traceSession
                .query(
                    """
                    SELECT slice.ts + slice.dur AS end_ts FROM slice
                    JOIN thread_track ON slice.track_id = thread_track.id
                    JOIN thread USING (utid)
                    JOIN process USING (upid)
                    WHERE process.name = '$pkg' AND slice.name = '$RESUME_SECTION'
                      AND slice.ts >= $launch AND slice.dur >= 0
                    ORDER BY slice.ts LIMIT 1
                    """.trimIndent(),
                ).firstOrNull()
                ?.long("end_ts") ?: error("T-PERF-02: HomeActivity did not resume after the launch ($RESUME_SECTION)")
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
                      AND slice.ts >= $resumed AND slice.dur > 0
                    ORDER BY slice.ts LIMIT 1
                    """.trimIndent(),
                ).firstOrNull()
                ?.long("end_ts") ?: error("T-PERF-02: $pkg drew no frame after HomeActivity resumed")
        return listOf(Measurement(NAME, (drawn - launch) / 1_000_000.0))
    }

    /**
     * Where the launch began. API 29+ opens it as `launchingActivity#N` and closes it with
     * `launchingActivity#N:completed-<kind>:<target>`; the later `launching: <target>` slice starts only when the system
     * reports the launch, AFTER the activity had already resumed (run 44227087271, API 34: launchingActivity#1 at
     * +0 ms, onResume at +34 ms, `launching:` at +56 ms). So the start is `launchingActivity#N` of the marker naming the
     * target; `launching: <target>` only where those slices do not exist.
     */
    private fun launchStart(
        session: TraceProcessor.Session,
        pkg: String,
    ): Long {
        val marker =
            session
                .query(
                    "SELECT name FROM slice WHERE name GLOB 'launchingActivity#*:completed*:$pkg' ORDER BY ts LIMIT 1",
                ).firstOrNull()
                ?.string("name")
        val name = marker?.substringBefore(':') ?: "launching: $pkg"
        return session
            .query("SELECT ts FROM slice WHERE name = '$name' ORDER BY ts LIMIT 1")
            .firstOrNull()
            ?.long("ts") ?: error("T-PERF-02: no '$name' slice in the trace (the Home intent did not launch)")
    }

    companion object {
        const val NAME = "warmReturnMs"

        /** `TraceSections.HOME_RESUME` in :core:common (not a dependency of this module). */
        private const val RESUME_SECTION = "strata.home.onResume"
    }
}
