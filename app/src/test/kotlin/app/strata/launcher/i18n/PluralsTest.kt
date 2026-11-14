package app.strata.launcher.i18n

import android.content.Context
import android.content.res.Configuration
import android.icu.text.PluralRules
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import app.strata.launcher.core.ui.R as UiR
import app.strata.launcher.feature.home.R as HomeR
import app.strata.launcher.feature.settings.R as SettingsR

/**
 * T-I18N-02 (R): "Hidden apps (n)" and "n notifications" for n = 0, 1, 2, 5, 11, 21, 100 in en, ru, ar and ja (plus de,
 * the first translated locale). Every count string is a `<plurals>` resource read through `getQuantityString`, so the
 * form is chosen by the locale's ICU plural rules; the expected categories are checked here, and the exact wording for
 * the locales Strata ships (en, de). ru, ar and ja have no translation yet: they fall back to the English text, which
 * must still show the number (in the locale's digits) in every category.
 */
@RunWith(AndroidJUnit4::class)
class PluralsTest {
    private val base: Context = ApplicationProvider.getApplicationContext()
    private val counts = listOf(0, 1, 2, 5, 11, 21, 100)

    private fun res(tag: String) =
        base
            .createConfigurationContext(
                Configuration(base.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(tag))
                },
            ).resources

    private fun badge(
        tag: String,
        n: Int,
    ) = res(tag).getQuantityString(UiR.plurals.badge_description, n, n)

    private fun hidden(
        tag: String,
        n: Int,
    ) = res(tag).getQuantityString(HomeR.plurals.list_end_hidden_apps, n, n)

    @Test
    fun english() {
        assertThat(counts.map { badge("en", it) })
            .containsExactly(
                "0 notifications",
                "1 notification",
                "2 notifications",
                "5 notifications",
                "11 notifications",
                "21 notifications",
                "100 notifications",
            ).inOrder()
        assertThat(
            counts.map { hidden("en", it) },
        ).containsExactlyElementsIn(counts.map { "Hidden apps ($it)" }).inOrder()
        assertThat(res("en").getQuantityString(SettingsR.plurals.settings_hidden_count, 1, 1)).isEqualTo("1 app hidden")
    }

    @Test
    fun german() {
        assertThat(counts.map { badge("de", it) })
            .containsExactly(
                "0 Benachrichtigungen",
                "1 Benachrichtigung",
                "2 Benachrichtigungen",
                "5 Benachrichtigungen",
                "11 Benachrichtigungen",
                "21 Benachrichtigungen",
                "100 Benachrichtigungen",
            ).inOrder()
        assertThat(hidden("de", 21)).isEqualTo("Ausgeblendete Apps (21)")
    }

    /** The ICU categories the resources are selected by (CLDR rules). */
    @Test
    fun icuCategories() {
        fun cats(tag: String) = counts.map { PluralRules.forLocale(Locale.forLanguageTag(tag)).select(it.toDouble()) }
        assertThat(cats("en")).containsExactly("other", "one", "other", "other", "other", "other", "other").inOrder()
        assertThat(cats("ru")).containsExactly("many", "one", "few", "many", "many", "one", "many").inOrder()
        assertThat(cats("ar")).containsExactly("zero", "one", "two", "few", "many", "many", "other").inOrder()
        assertThat(cats("ja")).containsExactly("other", "other", "other", "other", "other", "other", "other").inOrder()
    }

    /** Untranslated locales: every category resolves to a text with the number (no missing item, no crash). */
    @Test
    fun untranslatedLocalesStillShowTheNumber() {
        for (tag in listOf("ru", "ar", "ja")) {
            for (n in counts) {
                // %d follows the locale's digits (Arabic-Indic for ar).
                val digits = String.format(Locale.forLanguageTag(tag), "%d", n)
                assertWithMessage("$tag $n").that(badge(tag, n)).contains(digits)
                assertWithMessage("$tag $n").that(hidden(tag, n)).contains(digits)
            }
        }
    }

    /** Every plural in the shipped locales has the `one` and `other` items the en/de rules select. */
    @Test
    fun shippedPluralsHaveOneAndOther() {
        for (tag in listOf("en", "de")) {
            val r = res(tag)
            for (id in listOf(
                UiR.plurals.badge_description,
                UiR.plurals.index_letter_description,
                HomeR.plurals.fav_cap,
            )) {
                val one = r.getQuantityString(id, 1, 1, 1)
                val other = r.getQuantityString(id, 2, 2, 2)
                assertWithMessage("$tag ${r.getResourceEntryName(id)}").that(one).contains("1")
                assertWithMessage("$tag ${r.getResourceEntryName(id)}").that(other).contains("2")
            }
        }
    }
}
