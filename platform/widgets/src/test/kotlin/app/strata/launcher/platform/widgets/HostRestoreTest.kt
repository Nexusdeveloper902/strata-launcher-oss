package app.strata.launcher.platform.widgets

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** `APPWIDGET_HOST_RESTORED` extras checks of ARCHITECTURE §13 (level U, T-WIDGET-08 parsing half). */
class HostRestoreTest {
    @Test
    fun tWidget08_mapsOldToNew_invalidNewIsNull() {
        val map = HostRestore.parse(1, 1, intArrayOf(5, 6), intArrayOf(9, 0))
        assertThat(map).containsExactly(5, 9, 6, null)
    }

    @Test
    fun otherHostOrMissingHostId() {
        assertThat(HostRestore.parse(1, 2, intArrayOf(5), intArrayOf(9))).isNull()
        // EXTRA_HOST_ID is required.
        assertThat(HostRestore.parse(1, null, intArrayOf(5), intArrayOf(9))).isNull()
    }

    @Test
    fun malformedExtrasAreRejected() {
        assertThat(HostRestore.parse(1, 1, null, intArrayOf(9))).isNull()
        assertThat(HostRestore.parse(1, 1, intArrayOf(5), null)).isNull()
        assertThat(HostRestore.parse(1, 1, intArrayOf(5, 6), intArrayOf(9))).isNull()
        assertThat(HostRestore.parse(1, 1, intArrayOf(), intArrayOf())).isNull()
        val tooMany = IntArray(HostRestore.MAX_IDS + 1) { it + 1 }
        assertThat(HostRestore.parse(1, 1, tooMany, tooMany)).isNull()
        assertThat(HostRestore.parse(1, 1, intArrayOf(0, -2), intArrayOf(3, 4))).isNull()
    }
}
