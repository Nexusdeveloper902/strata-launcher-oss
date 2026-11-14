package app.strata.launcher.testing.fixtures.perf

/**
 * The blocking M9 budgets (IMPLEMENTATION_ROADMAP M9, PRODUCT_SPEC NFR-PERF-01…07, THEME_SYSTEM NFR-THEME-02). They are
 * asserted only by the `:benchmark` device harness on a physical device, never on an emulator or the JVM. A change to
 * any value needs a decision record (D-nnn), never a silent edit.
 */
object PerfBudgets {
    /** T-PERF-01, NFR-PERF-01: `timeToFullDisplayMs` median. */
    const val COLD_START_MEDIAN_MS = 700.0

    /** T-PERF-01, NFR-PERF-01: `timeToFullDisplayMs` p90. */
    const val COLD_START_P90_MS = 1_100.0

    /** T-PERF-02, NFR-PERF-01: warm return, `timeToInitialDisplayMs` median. */
    const val WARM_RETURN_MEDIAN_MS = 150.0

    /** T-PERF-03, NFR-PERF-02: janky frames during flings. */
    const val FLING_JANK_FRACTION = 0.01

    /** T-PERF-04, NFR-PERF-02: janky frames during an index scrub. */
    const val SCRUB_JANK_FRACTION = 0.03

    /** T-PERF-03/04, NFR-PERF-02: no frame longer than this. */
    const val LONGEST_FRAME_MS = 50.0

    /** T-PERF-05, NFR-PERF-03: one ranking pass over 500 apps, median. */
    const val SEARCH_RANK_MEDIAN_MS = 8.0

    /** T-PERF-05, NFR-PERF-03: one ranking pass over 500 apps, p95. */
    const val SEARCH_RANK_P95_MS = 16.0

    /** T-PERF-06, NFR-PERF-03: complete results with 2 000 contacts, p95. */
    const val SEARCH_CONTACTS_P95_MS = 50.0

    /** T-PERF-07, NFR-PERF-04: PSS at idle home after a full list scroll. */
    const val PSS_MAX_KB = 150L * 1024

    /** T-PERF-08, NFR-PERF-04: PSS growth over 50 search open/close cycles. */
    const val PSS_GROWTH_MAX_KB = 5L * 1024

    /** T-PERF-10, NFR-PERF-06: install or remove reflected in the list. */
    const val CHANGE_PROPAGATION_MS = 1_000.0

    /** T-THEME-53, NFR-PERF-06: apply to the first frame with the new theme. */
    const val THEME_APPLY_MS = 300.0

    /** T-THEME-44, NFR-THEME-02: parse + validate of a typical (< 8 KiB) theme. */
    const val THEME_PARSE_TYPICAL_MS = 5.0

    /** T-THEME-44, NFR-THEME-02: parse + validate of a 256 KiB theme (the "max" budget). */
    const val THEME_PARSE_MAX_MS = 30.0
}
