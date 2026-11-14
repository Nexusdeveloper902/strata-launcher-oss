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
