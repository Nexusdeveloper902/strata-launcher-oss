package app.strata.launcher.platform.apps

import app.strata.launcher.core.platformapi.apps.AppShortcut
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** FR-APPS-08 sheet order (level U): manifest shortcuts first, then dynamic ones, each by rank; at most five. */
class ShortcutOrderTest {
    private fun s(
        id: String,
        dynamic: Boolean,
        rank: Int,
        label: String = id,
    ) = AppShortcut(id, "p", 0, label, dynamic, rank)

    @Test
    fun manifestFirstThenDynamic_byRank_capped() {
        val ordered =
            AndroidShortcutSource.order(
                listOf(
                    s("d1", dynamic = true, rank = 1),
                    s("m2", dynamic = false, rank = 2),
                    s("d0", dynamic = true, rank = 0),
                    s("m0", dynamic = false, rank = 0),
                    s("blank", dynamic = false, rank = 1, label = " "),
                    s("m3", dynamic = false, rank = 3),
                    s("m4", dynamic = false, rank = 4),
                ),
            )
        assertThat(ordered.map { it.id }).containsExactly("m0", "m2", "m3", "m4", "d0").inOrder()
    }
}
