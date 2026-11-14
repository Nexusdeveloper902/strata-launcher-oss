package app.strata.launcher.core.common.trace

/**
 * Named trace sections for the `:benchmark` Macrobenchmarks (NFR-PERF-01, T-THEME-44), usable from pure Kotlin
 * modules. The Android binding (`android.os.Trace`) lives in `:app`; tests and the JVM use [NONE].
 * Section names are part of the benchmark contract: `strata.<area>.<what>`, see [TraceSections].
 */
interface Tracer {
    fun begin(name: String)

    fun end()

    companion object {
        val NONE: Tracer =
            object : Tracer {
                override fun begin(name: String) = Unit

                override fun end() = Unit
            }
    }
}

/** Runs [block] inside the section [name]; the section is closed even when [block] throws. */
inline fun <T> Tracer.section(
    name: String,
    block: () -> T,
): T {
    begin(name)
    try {
        return block()
    } finally {
        end()
    }
}

/** Section names read by the Macrobenchmarks in `:benchmark` (keep both sides in step). */
object TraceSections {
    const val APP_CREATE = "strata.app.onCreate"
    const val GRAPH_CREATE = "strata.graph.create"
    const val HOME_CREATE = "strata.home.onCreate"
    const val HOME_START = "strata.home.onStart"
    const val HOME_RESUME = "strata.home.onResume"
    const val THEME_BOOT = "strata.themeBoot"
    const val THEME_PARSE = "strata.themeParse"
    const val THEME_RESOLVE = "strata.themeResolve"
}
