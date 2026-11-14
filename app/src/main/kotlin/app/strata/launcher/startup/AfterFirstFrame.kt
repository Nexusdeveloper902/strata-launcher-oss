package app.strata.launcher.startup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * ARCHITECTURE §10 step 3 and the M9 fallback "defer non-critical startup work" (IMPLEMENTATION_ROADMAP M9): start-up
 * housekeeping that nothing on the first frame needs waits until home reports fully drawn, so it does not compete with
 * the theme and snapshot reads of step 2 for the disk and the CPU.
 *
 * Processes that never draw home (settings, the theme importer) still run the work after [fallbackMillis].
 */
class AfterFirstFrame(
    private val scope: CoroutineScope,
    private val fallbackMillis: Long = FALLBACK_MILLIS,
) {
    private val drawn = CompletableDeferred<Unit>()

    /** Home reported fully drawn (FR-LIFE-01). Idempotent. */
    fun firstFrameDrawn() {
        drawn.complete(Unit)
    }

    /** Runs [block] once home is fully drawn, or after [fallbackMillis], whichever comes first. */
    fun launch(
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> Unit,
    ): Job =
        scope.launch(context) {
            withTimeoutOrNull(fallbackMillis) { drawn.await() }
            block()
        }

    companion object {
        /** Well past the cold-start budget (≤ 1 100 ms p90, NFR-PERF-01) on RD-1. */
        const val FALLBACK_MILLIS = 3_000L
    }
}
