package app.strata.launcher.core.data.catalog

import app.strata.launcher.testing.fixtures.AppFixtures.app
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure snapshot diff (FR-LIFE-01, FR-LIFE-03, FR-LIST-04). */
class ReconcilerTest {
    private val a = app("Alpha", pkg = "p.a").copy(firstSeenAt = 100)
    private val b = app("Beta", pkg = "p.b").copy(firstSeenAt = 200)

    @Test
    fun fullPass_platformWins_firstSeenKept() {
        val renamed = b.copy(systemLabel = "Beta 2", firstSeenAt = 0)
        val c = app("Gamma", pkg = "p.c")
        val r = Reconciler.full(previous = listOf(a, b), platform = listOf(renamed, c), now = 999)
        assertThat(r.entries.map { it.systemLabel }).containsExactly("Beta 2", "Gamma")
        assertThat(r.entries.first { it.key == b.key }.firstSeenAt).isEqualTo(200)
        assertThat(r.entries.first { it.key == c.key }.firstSeenAt).isEqualTo(999)
        assertThat(r.removed).containsExactly(a.key)
        assertThat(r.upserts.map { it.key }).containsExactly(b.key, c.key)
    }

    @Test
    fun fullPass_noChange_noWrites() {
        val r = Reconciler.full(listOf(a, b), listOf(a.copy(firstSeenAt = 0), b.copy(firstSeenAt = 0)), now = 5)
        assertThat(r.changed).isFalse()
    }

    @Test
    fun packagePass_touchesOnlyThatPackageAndProfile() {
        val bWork = app("Beta", pkg = "p.b", serial = 10)
        val r =
            Reconciler.packages(
                previous = listOf(a, b, bWork),
                packages = setOf("p.b"),
                userSerial = 0,
                loaded = emptyList(),
                now = 7,
            )
        assertThat(r.removed).containsExactly(b.key)
        assertThat(r.entries.map { it.key }).containsExactly(a.key, bWork.key)
    }

    @Test
    fun duplicateKeysFromPlatformAreCollapsed() {
        val r = Reconciler.full(emptyList(), listOf(a, a), now = 1)
        assertThat(r.entries).hasSize(1)
    }
}
