package app.strata.launcher.core.data.list

import app.strata.launcher.core.model.AppOverride
import app.strata.launcher.core.model.Catalog
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.SecondaryLine
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
    }

    @Test
    fun snapshotFlagIsCarried() {
        assertThat(assemble(Catalog(emptyList(), fromSnapshot = true)).fromSnapshot).isTrue()
        assertThat(assemble(Catalog(emptyList(), fromSnapshot = false)).fromSnapshot).isFalse()
    }
}
