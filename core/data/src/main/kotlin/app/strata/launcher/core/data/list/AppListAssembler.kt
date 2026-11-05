package app.strata.launcher.core.data.list

import app.strata.launcher.core.model.AppEntry
import app.strata.launcher.core.model.AppFlags
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.AppOverride
import app.strata.launcher.core.model.AppSection
import app.strata.launcher.core.model.Catalog
import app.strata.launcher.core.model.IndexLetter
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.SecondaryLine
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.core.platformapi.apps.Profile
import app.strata.launcher.core.platformapi.system.SectionModel
import java.util.Locale

/**
 * Builds the visible app model once per upstream change (ARCHITECTURE §4): display labels, hidden
 * filter, duplicate disambiguation (FR-APPS-11), collation order and ICU sections (FR-LIST-01).
 * Pure; runs on `Dispatchers.Default`.
 */
object AppListAssembler {
    private val FALLBACK_SECTIONS = SectionModel(listOf("#")) { 0 }

    fun assemble(
        catalog: Catalog,
        overrides: Map<AppKey, AppOverride>,
        sectionModel: SectionModel,
        comparator: Comparator<String>,
        profiles: ProfileState? = null,
    ): AppList {
        val sections = if (sectionModel.labels.isEmpty()) FALLBACK_SECTIONS else sectionModel
        val entries = profiles?.apply(catalog.entries) ?: catalog.entries
        // Private-space rows get their own container (FR-APPS-10); they never enter the A–Z list or the index, and
        // no override applies to them (none is ever stored, P-5).
        val (privateEntries, regular) = entries.partition { it.profileKind == ProfileKind.PRIVATE }
        val all = regular.map { it.toVisible(overrides[it.key]) }
        val (hidden, shown) = all.partition { it.hidden }
        val withLines = disambiguate(shown)
        val order =
            compareBy(comparator) { it: VisibleApp -> it.displayLabel }
                .thenBy { it.profileKind.ordinal }
                .thenBy { it.secondaryLine?.detail.orEmpty() }
                .thenBy { it.key.component }
                .thenBy { it.key.userSerial }
        val sorted = withLines.sortedWith(order)
        // Hidden apps are disambiguated among themselves; they only appear in search (FR-SEARCH-03).
        val hiddenSorted = disambiguate(hidden).sortedWith(order)

        val buckets = arrayOfNulls<MutableList<VisibleApp>>(sections.labels.size)
        for (app in sorted) {
            val b = sections.bucketOf(app.displayLabel).coerceIn(0, sections.labels.size - 1)
            (buckets[b] ?: mutableListOf<VisibleApp>().also { buckets[b] = it }) +=
                app.copy(sectionKey = sections.labels[b])
        }

        val appSections = ArrayList<AppSection>()
        val index = ArrayList<IndexLetter>(sections.labels.size)
        sections.labels.forEachIndexed { i, label ->
            val apps = buckets[i]
            if (apps.isNullOrEmpty()) {
                index += IndexLetter(label, sectionIndex = -1, appCount = 0)
            } else {
                index += IndexLetter(label, sectionIndex = appSections.size, appCount = apps.size)
                appSections += AppSection(label, apps)
            }
        }
        val privateApps = disambiguate(privateEntries.map { it.toVisible(null) }).sortedWith(order)
        return AppList(
            appSections,
            index,
            hiddenCount = hidden.size,
            fromSnapshot = catalog.fromSnapshot,
            hiddenApps = hiddenSorted,
            privateApps = privateApps,
        )
    }

    private fun AppEntry.toVisible(override: AppOverride?): VisibleApp {
        val custom = override?.customLabel?.takeIf { it.isNotBlank() }
        return VisibleApp(
            key = key,
            packageName = packageName,
            displayLabel = custom ?: systemLabel.ifBlank { packageName },
            systemLabel = systemLabel,
            sectionKey = "",
            profileKind = profileKind,
            hidden = override?.hidden == true,
            flags = flags,
            lastUpdateTime = lastUpdateTime,
            renamed = custom != null,
            iconOverride = override?.icon,
        )
    }

    /**
     * FR-APPS-11: rows that share a label get a secondary line. Across profiles it names the profile;
     * within one profile it names the activity (same package) or the package tail (different packages).
     */
    internal fun disambiguate(apps: List<VisibleApp>): List<VisibleApp> {
        val groups = apps.groupBy { it.displayLabel.trim().lowercase(Locale.ROOT) }
        if (groups.values.none { it.size > 1 }) return apps
        val lines = HashMap<AppKey, SecondaryLine>()
        for (group in groups.values) {
            if (group.size < 2) continue
            val multiProfile = group.map { it.key.userSerial }.distinct().size > 1
            for (sameProfile in group.groupBy { it.key.userSerial }.values) {
                val details = if (sameProfile.size > 1) details(sameProfile) else emptyMap()
                for (app in sameProfile) {
                    lines[app.key] =
                        SecondaryLine(
                            profile = if (multiProfile) app.profileKind else null,
                            detail = details[app.key],
                        )
                }
            }
        }
        return apps.map { app -> lines[app.key]?.let { app.copy(secondaryLine = it) } ?: app }
    }

    private fun details(apps: List<VisibleApp>): Map<AppKey, String> {
        val samePackage = apps.map { it.packageName }.distinct().size == 1
        val byKey =
            apps.associate { app ->
                app.key to if (samePackage) activityName(app.key) else app.packageName.substringAfterLast('.')
            }
        // Fall back to the full name when the short forms still collide.
        if (byKey.values.distinct().size == byKey.size) return byKey
        return apps.associate { app ->
            app.key to if (samePackage) app.key.component.substringAfter('/') else app.packageName
        }
    }

    private fun activityName(key: AppKey): String {
        val simple = key.component.substringAfter('/').substringAfterLast('.')
        val trimmed = simple.removeSuffix("Activity")
        return trimmed.ifEmpty { simple }
    }
}

/**
 * What the profiles say now ([app.strata.launcher.core.platformapi.apps.ProfileSource]); it wins over the catalog's
 * load-time flags, so a pause or a lock shows within one assembly, before the catalog has reloaded (T-LIFE-08/09).
 */
data class ProfileState(
    /** Profiles in quiet mode (paused work, locked private space). */
    val quiet: Set<Long>,
    /** Private-space profiles that are unlocked. */
    val unlockedPrivate: Set<Long>,
) {
    /** Private entries only of unlocked spaces; [AppFlags.QUIET] set exactly on entries of quiet profiles. */
    fun apply(entries: List<AppEntry>): List<AppEntry> =
        entries.mapNotNull { e ->
            when {
                e.profileKind == ProfileKind.PRIVATE -> {
                    e.takeIf { it.key.userSerial in unlockedPrivate }
                }

                e.key.userSerial in quiet -> {
                    if (e.hasFlag(AppFlags.QUIET)) e else e.copy(flags = e.flags or AppFlags.QUIET)
                }

                e.hasFlag(AppFlags.QUIET) -> {
                    e.copy(flags = e.flags and AppFlags.QUIET.inv())
                }

                else -> {
                    e
                }
            }
        }

    companion object {
        fun of(profiles: List<Profile>): ProfileState =
            ProfileState(
                quiet = profiles.filter { it.quietMode }.mapTo(HashSet()) { it.serial },
                unlockedPrivate =
                    profiles
                        .filter { it.kind == ProfileKind.PRIVATE && !it.quietMode }
                        .mapTo(HashSet()) { it.serial },
            )
    }
}
