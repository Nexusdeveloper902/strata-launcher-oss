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
    /** Package `lastUpdateTime`: the icon cache key (ARCHITECTURE §9). */
    val lastUpdateTime: Long = 0L,
    /** A custom label is set (FR-APPS-05: the sheet offers "Reset name"). */
    val renamed: Boolean = false,
    /** FR-APPS-06: the per-app icon chosen in S-ICON (part of the icon request). */
    val iconOverride: IconOverride? = null,
) {
    fun hasFlag(flag: Int): Boolean = flags and flag != 0

    /** FR-LIST-08 / FR-APPS-03 helpers. */
    val suspended: Boolean get() = hasFlag(AppFlags.SUSPENDED)

    /** FR-APPS-09: the work profile is paused; a tap offers "Turn on work apps". */
    val paused: Boolean get() = hasFlag(AppFlags.QUIET)

    /** FR-APPS-10: a private-space app. Never persisted, logged or backed up (P-5). */
    val isPrivate: Boolean get() = profileKind == ProfileKind.PRIVATE
}

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
    /** Hidden apps (not in [sections]), in collation order; search may show them (FR-SEARCH-03). */
    val hiddenApps: List<VisibleApp> = emptyList(),
    /**
     * FR-APPS-10: apps of an unlocked private space, in collation order, for its own container at the list end. Not in
     * [sections], [index] or [allApps]; empty while the space is locked. Memory only.
     */
    val privateApps: List<VisibleApp> = emptyList(),
) {
    /** Every non-private app: shown sections first, then hidden ones (the search index input). */
    val allApps: List<VisibleApp> get() = sections.flatMap { it.apps } + hiddenApps

    val appCount: Int get() = sections.sumOf { it.apps.size }

    companion object {
        val EMPTY = AppList(emptyList(), emptyList(), hiddenCount = 0, fromSnapshot = true)
    }
}
