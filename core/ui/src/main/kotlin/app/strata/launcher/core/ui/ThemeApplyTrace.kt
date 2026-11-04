package app.strata.launcher.core.ui

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/**
 * T-THEME-53 (NFR-PERF-06): an async trace section from the tap that applies a theme to the first frame drawn with
 * it, read by the `:benchmark` Macrobenchmark through `TraceSectionMetric`. Async sections need API 29; below that
 * nothing is traced. Costs nothing when tracing is off.
 */
object ThemeApplyTrace {
    const val SECTION = "strata.themeApply"
    private val cookies = AtomicInteger()

    @Volatile private var open = NONE

    @Volatile private var fromGeneration = NONE

    /** The user applied a theme while [currentGeneration] is shown. */
    fun begin(currentGeneration: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val previous = open
        if (previous != NONE) Trace.endAsyncSection(SECTION, previous)
        val c = cookies.incrementAndGet()
        fromGeneration = currentGeneration
        open = c
        Trace.beginAsyncSection(SECTION, c)
    }

    /** A frame showing [generation] was drawn: ends the section once a newer generation than the tap's is on screen. */
    fun frameDrawn(generation: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val c = open
        if (c == NONE || generation <= fromGeneration) return
        open = NONE
        Trace.endAsyncSection(SECTION, c)
    }

    private const val NONE = -1
}
