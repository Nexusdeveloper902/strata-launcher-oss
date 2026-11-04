package app.strata.launcher.feature.home.list

import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.AppSection
import app.strata.launcher.core.model.IndexLetter
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.feature.home.PrivateSpaceUi

/**
 * One lazy-list item of S-LIST. Keys are stable across updates (ARCHITECTURE M1 risk: 500 rows). They are computed
 * once per item: the lazy list asks for keys while it measures, and a getter built a new string on every call.
 */
sealed interface ListItem {
    val key: String

    data class Header(val label: String, val sectionIndex: Int) : ListItem {
        override val key: String = "h:$label"
    }

    data class Row(val app: VisibleApp) : ListItem {
        override val key: String = rowKey(app)
    }

    /** Two-column line: [start] and [end] belong to the same section (column-major, FR-LIST-07). */
    data class Pair(val start: VisibleApp, val end: VisibleApp?) : ListItem {
        override val key: String = "p:" + rowKey(start)
    }

    data class End(val hiddenCount: Int) : ListItem {
        override val key: String = "end"
    }

    /** FR-APPS-10: the private-space container's heading, with Lock (unlocked) and Hide. */
    data class PrivateHeader(val locked: Boolean) : ListItem {
        override val key: String = "private_header"
    }

    /** An app of the unlocked private space (memory only). */
    data class PrivateRow(val app: VisibleApp) : ListItem {
        override val key: String = "pv:" + rowKey(app)
    }

    /** Locked: the "Private space — Unlock" entry (absent when the entry point is hidden, API 36). */
    data object PrivateUnlock : ListItem {
        override val key: String = "private_unlock"
    }

    /** Unlocked but empty. */
    data object PrivateEmpty : ListItem {
        override val key: String = "private_empty"
    }
}

fun rowKey(app: VisibleApp): String = "a:${app.key.component}:${app.key.userSerial}"

/** Lazy items plus the item position where each section starts (for the index jump). */
class ListModel(val items: List<ListItem>, val sectionStarts: IntArray)

object ListItems {
    /**
     * Flattens the sections. In two columns each section fills its start column top to bottom and then the
     * end column, so reading order stays section-major (FR-LIST-07, INTERACTION_SPEC §9).
     */
    fun build(
        list: AppList,
        twoColumns: Boolean,
        headers: Boolean,
        privateSpace: PrivateSpaceUi? = null,
    ): ListModel {
        val items = ArrayList<ListItem>(list.appCount + list.sections.size + 1)
        val starts = IntArray(list.sections.size)
        list.sections.forEachIndexed { s, section ->
            starts[s] = items.size
            if (headers) items += ListItem.Header(section.label, s)
            if (twoColumns) {
                val apps = section.apps
                val half = (apps.size + 1) / 2
                for (i in 0 until half) items += ListItem.Pair(apps[i], apps.getOrNull(i + half))
            } else {
                section.apps.mapTo(items) { ListItem.Row(it) }
            }
        }
        items += privateItems(privateSpace)
        items += ListItem.End(list.hiddenCount)
        return ListModel(items, starts)
    }

    /**
     * FR-APPS-10 container at the list end: heading + rows while unlocked; heading + "Unlock" while locked; nothing
     * while locked with the entry point hidden (API 36), so a hidden space leaves no trace in the list.
     */
    fun privateItems(space: PrivateSpaceUi?): List<ListItem> =
        when {
            space == null -> emptyList()
            space.locked && space.entryHidden -> emptyList()
            space.locked -> listOf(ListItem.PrivateHeader(locked = true), ListItem.PrivateUnlock)
            space.apps.isEmpty() -> listOf(ListItem.PrivateHeader(locked = false), ListItem.PrivateEmpty)
            else -> listOf(ListItem.PrivateHeader(locked = false)) + space.apps.map { ListItem.PrivateRow(it) }
        }
}

/**
 * FR-APPS-09 "Work" filter: only work-profile apps, sections and index rebuilt (empty letters dimmed); hidden and
 * private apps are not part of it.
 */
fun AppList.onlyWork(): AppList {
    val kept = sections.map { s -> s.copy(apps = s.apps.filter { it.profileKind == ProfileKind.WORK }) }
    val newIndexOf = IntArray(kept.size) { -1 }
    val out = ArrayList<AppSection>()
    kept.forEachIndexed { i, s ->
        if (s.apps.isNotEmpty()) {
            newIndexOf[i] = out.size
            out += s
        }
    }
    val letters =
        index.map { letter ->
            val mapped = if (letter.sectionIndex >= 0) newIndexOf[letter.sectionIndex] else -1
            IndexLetter(letter.label, mapped, if (mapped >= 0) out[mapped].apps.size else 0)
        }
    return copy(sections = out, index = letters, privateApps = emptyList())
}
