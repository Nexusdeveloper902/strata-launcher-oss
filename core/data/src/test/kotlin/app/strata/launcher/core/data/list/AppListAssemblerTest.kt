package app.strata.launcher.core.data.list

import app.strata.launcher.core.model.AppFlags
import app.strata.launcher.core.model.AppOverride
import app.strata.launcher.core.model.Catalog
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.SecondaryLine
import app.strata.launcher.core.platformapi.apps.Profile
import app.strata.launcher.testing.fakes.FakeCollation
import app.strata.launcher.testing.fixtures.AppFixtures
import app.strata.launcher.testing.fixtures.AppFixtures.app
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** `AppListAssembler` with the JVM collation stand-in (ICU behaviour: `:platform:system` tests). */
class AppListAssemblerTest {
    private val collation = FakeCollation()

    private fun assemble(
        catalog: Catalog,
        overrides: Map<app.strata.launcher.core.model.AppKey, AppOverride> = emptyMap(),
    ) = AppListAssembler.assemble(
        catalog,
        overrides,
        collation.sections(listOf("en")),
        collation.comparator(listOf("en")),
    )

    /** T-APPS-17 (data half): duplicates in one package and across profiles get distinct secondary lines. */
    @Test
    fun tApps17_duplicateLabelsGetDistinctSecondaryLines() {
        val list = assemble(Catalog(AppFixtures.APPS500, fromSnapshot = false))
        val cameras = list.sections.flatMap { it.apps }.filter { it.displayLabel == "Camera" }
        assertThat(cameras.map { it.secondaryLine })
            .containsExactly(SecondaryLine(detail = "Photo"), SecondaryLine(detail = "Video"))
        val calculators = list.sections.flatMap { it.apps }.filter { it.displayLabel == "Calculator" }
        assertThat(calculators.map { it.secondaryLine })
            .containsExactly(SecondaryLine(profile = ProfileKind.MAIN), SecondaryLine(profile = ProfileKind.WORK))
        val unique = list.sections.flatMap { it.apps }.first { it.displayLabel == "Zebra" }
        assertThat(unique.secondaryLine).isNull()
    }

    @Test
    fun differentPackagesSameLabel_usePackageTail() {
        val list = assemble(Catalog(listOf(app("Notes", pkg = "a.one"), app("Notes", pkg = "b.two")), false))
        assertThat(
            list.sections
                .single()
                .apps
                .map { it.secondaryLine?.detail },
        ).containsExactly("one", "two")
    }

    /** FR-LIST-01: sorted by collation, grouped into buckets; "#" holds digits and symbols; every bucket is in the index. */
    @Test
    fun sortedAndBucketed_withFullIndex() {
        val list = assemble(Catalog(AppFixtures.APPS500, fromSnapshot = false))
        assertThat(list.appCount).isEqualTo(500)
        assertThat(list.index.map { it.label }).isEqualTo(FakeCollation.LABELS)
        val latin = list.sections.filter { it.label.length == 1 && it.label[0] in 'A'..'Z' }
        assertThat(latin).hasSize(26)
        val digits =
            list.sections
                .first { it.label == "#" }
                .apps
                .map { it.displayLabel }
        assertThat(digits).containsAtLeast("3D Viewer", "#hash", "2048 Tiles")
        for (section in list.sections) {
            val labels = section.apps.map { it.displayLabel }
            assertThat(labels).isInOrder(collation.comparator(listOf("en")))
            assertThat(section.apps.all { it.sectionKey == section.label }).isTrue()
        }
        list.index.filterNot { it.isEmpty }.forEach { letter ->
            assertThat(list.sections[letter.sectionIndex].label).isEqualTo(letter.label)
            assertThat(list.sections[letter.sectionIndex].apps).hasSize(letter.appCount)
        }
    }

    /** FR-LIST-06 count and FR-APPS-04 filter; custom labels win; PRIVATE rows never enter the list. */
    @Test
    fun hiddenCustomAndPrivate() {
        val a = app("Alpha")
        val b = app("Beta")
        val c = app("Gamma")
        val p = app("Private thing", serial = 99, kind = ProfileKind.PRIVATE)
        val list =
            assemble(
                Catalog(listOf(a, b, c, p), false),
                mapOf(
                    a.key to AppOverride(a.key, hidden = true),
                    b.key to AppOverride(b.key, customLabel = "Zulu"),
                ),
            )
        val shown = list.sections.flatMap { it.apps }
        assertThat(shown.map { it.displayLabel }).containsExactly("Gamma", "Zulu").inOrder()
        assertThat(shown.first { it.displayLabel == "Zulu" }.systemLabel).isEqualTo("Beta")
        assertThat(list.hiddenCount).isEqualTo(1)
        // FR-SEARCH-03: hidden apps stay available to search; private ones never (FR-APPS-10).
        assertThat(list.hiddenApps.map { it.displayLabel }).containsExactly("Alpha")
        assertThat(list.hiddenApps.single().hidden).isTrue()
        assertThat(list.allApps.map { it.displayLabel }).containsExactly("Gamma", "Zulu", "Alpha").inOrder()
    }

    @Test
    fun snapshotFlagIsCarried() {
        assertThat(assemble(Catalog(emptyList(), fromSnapshot = true)).fromSnapshot).isTrue()
        assertThat(assemble(Catalog(emptyList(), fromSnapshot = false)).fromSnapshot).isFalse()
    }

    /** T-APPS-15 (data half): private-space apps form their own container, outside the A–Z list, index and search input. */
    @Test
    fun tApps15_privateAppsInOwnContainer() {
        val a = app("Alpha")
        val p1 = app("Zeta vault", serial = 99, kind = ProfileKind.PRIVATE)
        val p2 = app("Bank", serial = 99, kind = ProfileKind.PRIVATE)
        val list = assemble(Catalog(listOf(a, p1, p2), false))
        assertThat(list.sections.flatMap { it.apps }.map { it.displayLabel }).containsExactly("Alpha")
        assertThat(list.index.sumOf { it.appCount }).isEqualTo(1)
        assertThat(list.allApps.map { it.displayLabel }).containsExactly("Alpha")
        assertThat(list.privateApps.map { it.displayLabel }).containsExactly("Bank", "Zeta vault").inOrder()
        assertThat(list.privateApps.all { it.isPrivate }).isTrue()
    }

    /**
     * T-LIFE-08 / T-LIFE-09 (data half): the profiles' current state wins over the catalog's load-time flags: a paused
     * work profile marks its rows QUIET, a resumed one clears it, a locked private space contributes nothing.
     */
    @Test
    fun profileStateMarksPausedAndDropsLockedPrivate() {
        val main = app("Alpha")
        val work = app("Mail", serial = 10)
        val private = app("Bank", serial = 99, kind = ProfileKind.PRIVATE)
        val catalog = Catalog(listOf(main, work, private), false)

        fun withProfiles(vararg profiles: Profile) =
            AppListAssembler.assemble(
                catalog,
                emptyMap(),
                collation.sections(listOf("en")),
                collation.comparator(listOf("en")),
                ProfileState.of(profiles.toList()),
            )
        val paused =
            withProfiles(
                Profile(0, ProfileKind.MAIN, quietMode = false, available = true),
                Profile(10, ProfileKind.WORK, quietMode = true, available = false),
                Profile(99, ProfileKind.PRIVATE, quietMode = true, available = false),
            )
        assertThat(paused.allApps.first { it.displayLabel == "Mail" }.paused).isTrue()
        assertThat(paused.allApps.first { it.displayLabel == "Alpha" }.paused).isFalse()
        assertThat(paused.privateApps).isEmpty()

        val resumed =
            AppListAssembler.assemble(
                Catalog(listOf(main, work.copy(flags = AppFlags.QUIET), private), false),
                emptyMap(),
                collation.sections(listOf("en")),
                collation.comparator(listOf("en")),
                ProfileState.of(
                    listOf(
                        Profile(10, ProfileKind.WORK, quietMode = false, available = true),
                        Profile(99, ProfileKind.PRIVATE, quietMode = false, available = true),
                    ),
                ),
            )
        assertThat(resumed.allApps.first { it.displayLabel == "Mail" }.paused).isFalse()
        assertThat(resumed.privateApps.map { it.displayLabel }).containsExactly("Bank")
        assertThat(resumed.privateApps.single().paused).isFalse()
    }
}
