package app.strata.launcher.core.model

/**
 * Secondary row line that tells duplicate labels apart (FR-APPS-11). [profile] is shown as the
 * localised profile name, [detail] as plain text (activity short name or package tail).
 * The UI joins both with a localised format string.
 */
data class SecondaryLine(
    val profile: ProfileKind? = null,
    val detail: String? = null,
)

/**
 * The visible app model shared by home, list and search (ARCHITECTURE §4 "Derived state").
 * Built once per upstream change by `AppListAssembler`.
 */
data class VisibleApp(
    val key: AppKey,
    val packageName: String,
    /** Custom label if set, else the system label. */
    val displayLabel: String,
    val systemLabel: String,
    /** Label of the collation bucket ([AppSection.label]). */
    val sectionKey: String,
    val profileKind: ProfileKind,
    val hidden: Boolean,
    /** Alerting notification count; 0 = no badge (badge source arrives in M7). */
    val badge: Int = 0,
    val secondaryLine: SecondaryLine? = null,
    val flags: Int = 0,
)

/** One non-empty section of the A–Z list. */
data class AppSection(val label: String, val apps: List<VisibleApp>)

/** One letter of the alphabet index. [sectionIndex] is the list section it jumps to, or -1 when empty. */
data class IndexLetter(val label: String, val sectionIndex: Int, val appCount: Int) {
    val isEmpty: Boolean get() = sectionIndex < 0
}

/** The assembled A–Z list (FR-LIST-01, FR-LIST-06). */
data class AppList(
    val sections: List<AppSection>,
    /** Every bucket of the locale's index, in order, including empty ones (drawn dimmed, FR-LIST-02). */
    val index: List<IndexLetter>,
    val hiddenCount: Int,
    /** True while the list still comes from the persisted snapshot (FR-LIFE-01). */
    val fromSnapshot: Boolean,
) {
    val appCount: Int get() = sections.sumOf { it.apps.size }

    companion object {
        val EMPTY = AppList(emptyList(), emptyList(), hiddenCount = 0, fromSnapshot = true)
    }
}
