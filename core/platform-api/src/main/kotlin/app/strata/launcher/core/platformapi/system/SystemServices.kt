package app.strata.launcher.core.platformapi.system

/** INTERACTION_SPEC §4.3 haptic events. */
enum class HapticKind {
    /** Alphabet index letter change. */
    TICK,
    LONG_PRESS,
    GESTURE_START,
    GESTURE_END,
}

/**
 * Haptic feedback through `View.performHapticFeedback` (respects the system setting, no `VIBRATE`).
 * Callers gate it with the master switch (FR-SET-05) and the theme (FR-THEME-17).
 */
fun interface Haptics {
    fun tick(kind: HapticKind)
}

/**
 * Index buckets for the user's locales (ICU `AlphabeticIndex`, FR-LIST-01). Pure Kotlin; no
 * `android.widget.SectionIndexer`. [bucketOf] maps a label to an index into [labels].
 */
class SectionModel(
    val labels: List<String>,
    val bucketOf: (String) -> Int,
)

/** Locale collation (ICU `Collator`) and sections (ICU `AlphabeticIndex`), ARCHITECTURE §3. */
interface Collation {
    /** Thread-safe comparator for labels under [locales] (BCP-47 tags, first = primary). */
    fun comparator(locales: List<String>): Comparator<String>

    /** Buckets for [locales]. The digits/symbols bucket is labelled [DIGIT_BUCKET]. */
    fun sections(locales: List<String>): SectionModel

    companion object {
        const val DIGIT_BUCKET = "#"
    }
}
