package app.strata.launcher.feature.home

import app.strata.launcher.core.model.AppFlags
import app.strata.launcher.core.ui.IndexExclusion
import app.strata.launcher.core.ui.IndexMath
import app.strata.launcher.testing.fixtures.SearchFixtures.visible
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.TimeZone

/** Pure home rules (level U). */
class HomeLogicTest {
    /** T-GEST-08 (U half): every gesture row of INTERACTION_SPEC §4.1 that is used names a non-gesture alternative. */
    @Test
    fun tGest08_everyGestureHasAnAlternative() {
        val spec = findSpec("INTERACTION_SPEC.md").readLines()
        val start = spec.indexOfFirst { it.startsWith("### 4.1") }
        val end = spec.indexOfFirst { it.startsWith("### 4.2") }
        val rows =
            spec
                .subList(start, end)
                .filter { it.startsWith("|") }
                .drop(2) // header + separator
                .map { line -> line.trim('|').split('|').map { it.trim() } }
        assertThat(rows.size).isAtLeast(10)
        val header =
            spec
                .subList(start, end)
                .first { it.startsWith("|") }
                .trim('|')
                .split('|')
                .map { it.trim() }
        val alt = header.indexOf("Alternative")
        val result = header.indexOf("Result")
        assertThat(alt).isAtLeast(0)
        for (row in rows) {
            if (row[result].contains("not used")) continue
            assertThat(row[alt]).isNotEmpty()
            assertThat(row[alt]).isNotEqualTo("—")
        }
    }

    /** T-GEST-06/07 (U half): full index rect, or 200 dp around the last touched letter, clamped to the index. */
    @Test
    fun tGest06_07_exclusionGeometry() {
        assertThat(IndexMath.exclusionSpan(IndexExclusion.NONE, 1000f, null, 200f)).isNull()
        assertThat(IndexMath.exclusionSpan(IndexExclusion.FULL, 1000f, 300f, 200f)).isEqualTo(0f..1000f)
        assertThat(IndexMath.exclusionSpan(IndexExclusion.AROUND_TOUCH, 1000f, 500f, 200f)).isEqualTo(400f..600f)
        assertThat(IndexMath.exclusionSpan(IndexExclusion.AROUND_TOUCH, 1000f, 20f, 200f)).isEqualTo(0f..120f)
        assertThat(IndexMath.exclusionSpan(IndexExclusion.AROUND_TOUCH, 1000f, null, 200f)).isEqualTo(400f..600f)
    }

    /** VISUAL_SPEC §9.4: tabular `HH:MM` in 24 h; 12 h without a leading zero plus the period marker. */
    @Test
    fun clockFormat() {
        val tz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val t = 21L * 3600_000 + 5 * 60_000 + 7_000 // 21:05:07 UTC
            assertThat(ClockFormat.format(t, use24h = true, showSeconds = false, locale = Locale.US))
                .isEqualTo(ClockText("21", "05", null, null))
            assertThat(ClockFormat.format(t, use24h = false, showSeconds = true, locale = Locale.US))
                .isEqualTo(ClockText("9", "05", "07", "PM"))
            assertThat(
                ClockFormat.format(0L, use24h = false, showSeconds = false, locale = Locale.US).hours,
            ).isEqualTo("12")
        } finally {
            TimeZone.setDefault(tz)
        }
    }

    /** FR-APPS-03: system → none; updated system → "Uninstall updates"; others → Uninstall. */
    @Test
    fun uninstallKinds() {
        assertThat(uninstallKind(visible("A"))).isEqualTo(UninstallKind.UNINSTALL)
        assertThat(uninstallKind(visible("A").copy(flags = AppFlags.SYSTEM))).isEqualTo(UninstallKind.NONE)
        assertThat(uninstallKind(visible("A").copy(flags = AppFlags.SYSTEM or AppFlags.UPDATED_SYSTEM)))
            .isEqualTo(UninstallKind.UNINSTALL_UPDATES)
    }

    private fun findSpec(name: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, "spec/$name")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        error("spec/$name not found")
    }
}
