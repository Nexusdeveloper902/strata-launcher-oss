package app.strata.launcher.platform.system

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.platformapi.system.Collation
import app.strata.launcher.testing.fixtures.AppFixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** FR-LIST-01, NFR-I18N-03: the `android.icu` adapter (Robolectric runs the platform ICU). */
@RunWith(AndroidJUnit4::class)
class IcuCollationTest {
    private val icu = IcuCollation()

    private fun sectionOf(
        label: String,
        locales: List<String>,
    ): String {
        val m = icu.sections(locales)
        return m.labels[m.bucketOf(label)]
    }

    /** T-LIST-01: under [sv, en], Å, Ä, Ö follow Z and items sort by Swedish collation. */
    @Test
    fun tList01_swedishSections() {
        val locales = listOf("sv", "en")
        val labels = icu.sections(locales).labels
        val z = labels.indexOf("Z")
        assertThat(z).isAtLeast(0)
        assertThat(labels.subList(z, z + 4)).containsExactly("Z", "Å", "Ä", "Ö").inOrder()
        assertThat(sectionOf("Åka", locales)).isEqualTo("Å")
        assertThat(sectionOf("Ärlig", locales)).isEqualTo("Ä")
        assertThat(sectionOf("Öra", locales)).isEqualTo("Ö")
        assertThat(sectionOf("Zebra", locales)).isEqualTo("Z")
        val sorted = listOf("Öra", "Zebra", "Ärlig", "Åka").sortedWith(icu.comparator(locales))
        assertThat(sorted).containsExactly("Zebra", "Åka", "Ärlig", "Öra").inOrder()
    }

    /** T-LIST-02: digits and symbols go to "#" (the last section, owner decision); under en, "Ångström" is in "A". */
    @Test
    fun tList02_digitBucket() {
        val en = listOf("en")
        assertThat(sectionOf("3D Viewer", en)).isEqualTo(Collation.DIGIT_BUCKET)
        assertThat(sectionOf("#hash", en)).isEqualTo(Collation.DIGIT_BUCKET)
        assertThat(sectionOf("Ångström", en)).isEqualTo("A")
        assertThat(icu.sections(en).labels.last()).isEqualTo(Collation.DIGIT_BUCKET)
        assertThat(icu.sections(en).labels.first()).isEqualTo("A")
    }

    /** T-LIST-03: for each locale alone, the non-empty sections of `APPS500` equal the golden file. */
    @Test
    fun tList03_localeFixtureSet() {
        val dir = File(checkNotNull(System.getProperty("strata.goldenDir")))
        val record = System.getProperty("strata.recordGoldens") == "true"
        for (locale in LOCALES) {
            val m = icu.sections(listOf(locale))
            val used =
                AppFixtures.APPS500
                    .map { m.bucketOf(it.systemLabel) }
                    .toSortedSet()
                    .map { m.labels[it] }
            val actual = used.joinToString("\n", postfix = "\n")
            val golden = File(dir, "$locale.txt")
            if (record) {
                dir.mkdirs()
                golden.writeText(actual)
            }
            assertThat(golden.exists()).isTrue()
            assertThat(actual).isEqualTo(golden.readText())
            assertThat(used).contains(Collation.DIGIT_BUCKET)
        }
    }

    @Test
    fun comparatorIsLocaleAware_andCached() {
        assertThat(icu.comparator(listOf("en"))).isSameInstanceAs(icu.comparator(listOf("en")))
        assertThat(icu.sections(listOf("de"))).isSameInstanceAs(icu.sections(listOf("de")))
        val sorted = listOf("b", "Ä", "a", "Z").sortedWith(icu.comparator(listOf("de")))
        assertThat(sorted).containsExactly("a", "Ä", "b", "Z").inOrder()
    }

    @Test
    fun hapticConstantsPerApi() {
        assertThat(ViewHaptics.constant(app.strata.launcher.core.platformapi.system.HapticKind.TICK, 34))
            .isEqualTo(android.view.HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
        assertThat(ViewHaptics.constant(app.strata.launcher.core.platformapi.system.HapticKind.TICK, 26))
            .isEqualTo(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    private companion object {
        val LOCALES = listOf("sv", "es", "de", "ja", "zh", "ru", "ar")
    }
}
