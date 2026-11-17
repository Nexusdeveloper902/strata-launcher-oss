package app.strata.launcher.feature.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.TimeZone

/** T-PERF-13: the header's date key changes exactly at local midnight, in the zone the clock is shown in. */
class LocalDayTest {
    private val berlin = TimeZone.getTimeZone("Europe/Berlin")
    private val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
    private val utc = TimeZone.getTimeZone("UTC")

    /** 2026-11-17T00:00:00+01:00 (Berlin, winter time) = 2026-11-16T23:00:00Z. */
    private val berlinMidnight = 1_794_870_000_000L

    @Test
    fun tPerf13_changesAtLocalMidnightOnly() {
        val before = localDay(berlinMidnight - 1, berlin)
        val at = localDay(berlinMidnight, berlin)
        assertThat(at).isEqualTo(before + 1)
        // Every other minute of the day keeps the key: the date is not re-derived on minute ticks.
        val lastMinute = berlinMidnight + DAY - MINUTE
        assertThat(localDay(berlinMidnight + MINUTE, berlin)).isEqualTo(at)
        assertThat(localDay(lastMinute, berlin)).isEqualTo(at)
        assertThat(localDay(berlinMidnight + DAY, berlin)).isEqualTo(at + 1)
    }

    @Test
    fun tPerf13_timeZoneChangeMovesTheDay() {
        // 23:30 in Berlin on the 16th is already the 17th in Tokyo and still the 16th in UTC.
        val instant = berlinMidnight - 30 * MINUTE
        assertThat(localDay(instant, tokyo)).isEqualTo(localDay(instant, berlin) + 1)
        assertThat(localDay(instant, utc)).isEqualTo(localDay(instant, berlin))
    }

    @Test
    fun tPerf13_daylightSavingDayIsOneDay() {
        // 2026-03-29 has 23 hours in Berlin: 00:00 local on the 29th and on the 30th are one key apart.
        val march29 = 1_774_738_800_000L // 2026-03-29T00:00:00+01:00
        val march30 = march29 + 23 * HOUR // 2026-03-30T00:00:00+02:00
        assertThat(localDay(march30, berlin)).isEqualTo(localDay(march29, berlin) + 1)
        assertThat(localDay(march30 - 1, berlin)).isEqualTo(localDay(march29, berlin))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
