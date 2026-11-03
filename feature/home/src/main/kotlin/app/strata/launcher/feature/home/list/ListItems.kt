package app.strata.launcher.feature.home.list

import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.VisibleApp

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
        items += ListItem.End(list.hiddenCount)
        return ListModel(items, starts)
    }
}
