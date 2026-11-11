package app.strata.launcher.platform.notifications

import app.strata.launcher.core.model.BadgeKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** T-NOTIF-04 (U): the FR-NOTIF-02 default filter. */
class BadgeCounterTest {
    private val x = BadgeKey("fixture.x", 0)
    private var n = 0

    private fun f(
        key: BadgeKey = x,
        ongoing: Boolean = false,
        summary: Boolean = false,
        importance: Int = 3,
        canShowBadge: Boolean = true,
    ) = NotificationFacts("k${n++}", key, ongoing, summary, importance, canShowBadge)

    @Test
    fun tNotif04_twoAlertingOneOngoingOneSilent_countsTwo() {
        val counts = BadgeCounter.counts(listOf(f(), f(importance = 4), f(ongoing = true), f(importance = 2)))
        assertThat(counts).containsExactly(x, 2)
    }

    @Test
    fun summariesBlockedChannelsAndMinAreNotCounted() {
        val counts =
            BadgeCounter.counts(
                listOf(f(summary = true), f(canShowBadge = false), f(importance = 1), f(importance = 0)),
            )
        assertThat(counts).isEmpty()
    }

    @Test
    fun unknownRankingCountsAsAlerting_andProfilesAreSeparate() {
        val work = BadgeKey("fixture.x", 10)
        val counts = BadgeCounter.counts(listOf(f(importance = IMPORTANCE_UNKNOWN), f(key = work)))
        assertThat(counts).containsExactly(x, 1, work, 1)
    }
}
