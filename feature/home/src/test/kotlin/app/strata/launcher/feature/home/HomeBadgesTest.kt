package app.strata.launcher.feature.home

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.BadgeKey
import app.strata.launcher.core.model.BadgeStyle
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.platformapi.notifications.ListenerAccess
import app.strata.launcher.core.ui.AppRowTestTags
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.testing.fakes.FakeBadgeSource
import app.strata.launcher.testing.fixtures.AppFixtures.app
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Notification badges on home and in the list (level R/C): T-NOTIF-04, T-NOTIF-05, T-NOTIF-03 (home half). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HomeBadgesTest {
    @get:Rule
    val compose = createComposeRule()

    private val mail = app("Mail", pkg = "fixture.mail")
    private val maps = app("Maps", pkg = "fixture.maps")

    private fun harness(
        style: BadgeStyle,
        badges: FakeBadgeSource = FakeBadgeSource(ListenerAccess.CONNECTED),
    ): HomeHarness {
        badges.setActive(true)
        // 2 alerting from Mail; the ongoing and silent ones are filtered by the listener (BadgeCounterTest).
        badges.post(mapOf(BadgeKey("fixture.mail", mail.key.userSerial) to 2))
        return HomeHarness(
            apps = listOf(mail, maps),
            favorites = listOf(mail.key),
            settings = Settings(onboardingDone = true, badgesEnabled = true, badgeStyle = style),
            badges = badges,
        )
    }

    private fun favoriteTag() = HomeTestTags.favorite(harnessApp("Mail"))

    private lateinit var h: HomeHarness

    /** The text "2" inside a count badge inside [container]. */
    private fun countIn(container: String) =
        hasText("2") and hasAnyAncestor(hasTestTag(AppRowTestTags.BADGE_COUNT)) and
            hasAnyAncestor(hasTestTag(container))

    private fun harnessApp(label: String) =
        h.state.list!!
            .allApps
            .first { it.systemLabel == label }

    /** T-NOTIF-04 (R): count style → "2" on the favourite and on the list row. */
    @Test
    fun tNotif04_countOnFavoriteAndRow() {
        h = harness(BadgeStyle.COUNT)
        h.setContent(compose)
        compose.onNode(countIn(favoriteTag()), useUnmergedTree = true).assertExists()
        h.vm.onAction(HomeAction.OpenList)
        compose.waitForIdle()
        compose.onNode(countIn(AppListTestTags.row(harnessApp("Mail"))), useUnmergedTree = true).assertExists()
        // Maps has no notification: no badge at all.
        assertThat(harnessApp("Maps").badge).isEqualTo(0)
    }

    /** T-NOTIF-05 (C): the default style is a dot, spoken as "2 notifications". */
    @Test
    fun tNotif05_dotByDefault_spokenCount() {
        h = harness(BadgeStyle.DOT)
        h.setContent(compose)
        val dot =
            compose.onNode(
                hasTestTag(AppRowTestTags.BADGE_DOT) and hasAnyAncestor(hasTestTag(favoriteTag())),
                useUnmergedTree = true,
            )
        val description =
            dot.fetchSemanticsNode().config.getOrElse(
                SemanticsProperties.ContentDescription,
            ) { emptyList() }
        assertThat(description.joinToString()).contains("2 notifications")
        compose.onAllNodesWithTag(AppRowTestTags.BADGE_COUNT, useUnmergedTree = true).fetchSemanticsNodes().let {
            assertThat(it).isEmpty()
        }
    }

    /** T-NOTIF-03 (home half): access revoked → the badges vanish on the next check. */
    @Test
    fun tNotif03_revokedBadgesVanish() {
        val badges = FakeBadgeSource(ListenerAccess.CONNECTED)
        h = harness(BadgeStyle.COUNT, badges)
        h.setContent(compose)
        assertThat(harnessApp("Mail").badge).isEqualTo(2)
        badges.systemAccess = ListenerAccess.NOT_GRANTED
        kotlinx.coroutines.runBlocking { badges.refresh() }
        compose.waitForIdle()
        assertThat(harnessApp("Mail").badge).isEqualTo(0)
        compose.onNodeWithTag(favoriteTag()).assertExists()
        assertThat(
            compose.onAllNodesWithTag(AppRowTestTags.BADGE_COUNT, useUnmergedTree = true).fetchSemanticsNodes(),
        ).isEmpty()
    }
}
