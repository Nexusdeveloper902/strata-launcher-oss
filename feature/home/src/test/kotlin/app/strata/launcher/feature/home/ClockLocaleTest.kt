package app.strata.launcher.feature.home

import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.model.HourFormat
import app.strata.launcher.testing.fixtures.AppFixtures.app
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * T-I18N-05 (R), NFR-I18N-04, FR-THEME-12: the clock follows the locale's time pattern (separator, digits, position of
 * the day period) for en-US, en-GB, de, ja and ar; 12/24 h follows the system setting unless the theme overrides it.
 * The date half (skeleton → locale pattern) is T-THEME-13 in [ThemeTokensTest].
 */
@RunWith(AndroidJUnit4::class)
class ClockLocaleTest {
    @get:Rule
    val compose = createComposeRule()

    private val utc = TimeZone.getTimeZone("UTC")
    private val t = 21L * 3_600_000 + 5 * 60_000 + 7_000 // 21:05:07 UTC

    private fun clock(
        tag: String,
        use24h: Boolean,
        seconds: Boolean = false,
    ) = ClockFormat.format(t, use24h, seconds, Locale.forLanguageTag(tag), utc)

    private fun expectedFull(
        tag: String,
        skeleton: String,
    ): String {
        val l = Locale.forLanguageTag(tag)
        return SimpleDateFormat(
            DateFormat.getBestDateTimePattern(l, skeleton),
            l,
        ).apply { timeZone = utc }.format(Date(t))
    }

    @After
    fun resetSystem() {
        Settings.System.putString(
            ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver,
            Settings.System.TIME_12_24,
            null,
        )
    }

    @Test
    fun enUs() {
        val h12 = clock("en-US", use24h = false, seconds = true)
        assertThat(
            listOf(h12.hours, h12.minutes, h12.seconds, h12.period),
        ).containsExactly("9", "05", "07", "PM").inOrder()
        assertThat(h12.periodFirst).isFalse()
        assertThat(h12.time).isEqualTo("9:05:07")
        assertThat(clock("en-US", use24h = true).time).isEqualTo("21:05")
        assertThat(
            ClockFormat.format(0L, use24h = false, showSeconds = false, locale = Locale.US, timeZone = utc).hours,
        ).isEqualTo("12")
    }

    @Test
    fun enGb() {
        assertThat(clock("en-GB", use24h = true).time).isEqualTo("21:05")
        assertThat(clock("en-GB", use24h = true).period).isNull()
        assertThat(clock("en-GB", use24h = false).full).isEqualTo(expectedFull("en-GB", "hm"))
    }

    @Test
    fun german() {
        assertThat(clock("de", use24h = true).time).isEqualTo("21:05")
        assertThat(clock("de", use24h = true).full).isEqualTo(expectedFull("de", "Hm"))
        assertThat(clock("de", use24h = false).full).isEqualTo(expectedFull("de", "hm"))
    }

    /** ja writes the day period before the time (午後9:05). */
    @Test
    fun japanese() {
        val h12 = clock("ja", use24h = false)
        assertThat(h12.periodFirst).isTrue()
        assertThat(h12.period).isEqualTo("午後")
        assertThat(h12.full).isEqualTo(expectedFull("ja", "hm"))
        assertThat(clock("ja", use24h = true).time).isEqualTo("21:05")
    }

    /** ar uses its own digits; digits are not mirrored (VISUAL_SPEC §8.5), the period follows the locale. */
    @Test
    fun arabic() {
        val h24 = clock("ar", use24h = true)
        assertThat(h24.hours).isEqualTo(String.format(Locale.forLanguageTag("ar"), "%d", 21))
        assertThat(h24.full).isEqualTo(expectedFull("ar", "Hm"))
        val h12 = clock("ar", use24h = false)
        assertThat(h12.period).isNotNull()
        assertThat(h12.full).isEqualTo(expectedFull("ar", "hm"))
    }

    /** Literals between and around the fields stay: fr-CA writes "21 h 05 min 07 s", bg "21:05:07 ч.". */
    @Test
    fun literalsBetweenFieldsStay_frCaAndBgWithSeconds() {
        for (tag in listOf("fr-CA", "bg")) {
            val c = clock(tag, use24h = true, seconds = true)
            assertThat(c.full).isEqualTo(expectedFull(tag, "Hms"))
            // 24 h has no day period, so the shown time is exactly the locale's text.
            assertThat(c.time).isEqualTo(c.full)
            assertThat(c.time).contains("07")
        }
        assertThat(clock("fr-CA", use24h = true, seconds = true).time).contains("h")
    }

    /** The day period and its joining space are not part of the time; other literals are. */
    @Test
    fun periodIsNotPartOfTheTime() {
        assertThat(clock("en-US", use24h = false).time).isEqualTo("9:05")
        assertThat(clock("ja", use24h = false).time).isEqualTo("9:05")
    }

    /** A pattern the formatter rejects falls back to HH:mm / h:mm a instead of throwing on every tick. */
    @Test
    fun rejectedPatternFallsBack() {
        val us = Locale.US
        val h24 =
            ClockFormat.format(
                t,
                use24h = true,
                showSeconds = false,
                locale = us,
                timeZone = utc,
                pattern = "HH:mm jjj",
            )
        assertThat(h24.time).isEqualTo("21:05")
        val h12 =
            ClockFormat.format(
                t,
                use24h = false,
                showSeconds = true,
                locale = us,
                timeZone = utc,
                pattern = "h:mm:ss jjj a",
            )
        assertThat(h12.time).isEqualTo("9:05:07")
        assertThat(h12.period).isEqualTo("PM")
    }

    /** The clock (shown and spoken, one [ClockText]) follows the system 12/24 h setting unless the theme overrides it. */
    private fun spokenWith(
        system: String,
        override: HourFormat?,
    ): String {
        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        Settings.System.putString(resolver, Settings.System.TIME_12_24, system)
        val base = StrataDefault.resolved(systemDark = false)
        val theme = if (override == null) base else base.copy(clock = base.clock.copy(hourFormat = override))
        HomeHarness(listOf(app("Alpha"))).setContent(compose, theme)
        return compose
            .onNodeWithTag(HomeTestTags.CLOCK)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .single()
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun system24h() = assertThat(spokenWith("24", null)).doesNotContainMatch("AM|PM")

    @Test
    @Config(qualifiers = "en-rUS")
    fun system12h() = assertThat(spokenWith("12", null)).containsMatch("AM|PM")

    @Test
    @Config(qualifiers = "en-rUS")
    fun themeH12BeatsSystem24h() = assertThat(spokenWith("24", HourFormat.H12)).containsMatch("AM|PM")

    @Test
    @Config(qualifiers = "en-rUS")
    fun themeH24BeatsSystem12h() = assertThat(spokenWith("12", HourFormat.H24)).containsMatch("^\\d{2}:\\d{2}$")
}
