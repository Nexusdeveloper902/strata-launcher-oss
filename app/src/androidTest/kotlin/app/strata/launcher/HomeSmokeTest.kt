package app.strata.launcher

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.home.HomeActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Level I smoke test for the Gradle Managed Device matrix (API 26, 34, 36; NFR-COMPAT-01): HomeActivity launches
 * and shows the M0 placeholder (T-HOME-01). The Orchestrator clears app data between tests, so first-run state
 * is deterministic: the first-run flow comes first and passing it reveals home.
 */
@RunWith(AndroidJUnit4::class)
class HomeSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<HomeActivity>()

    @Test
    fun launchesAndShowsPlaceholder() {
        compose.waitForIdle()
        compose.passOnboarding()
        compose.waitUntil(timeoutMillis = 10_000) { compose.count(HomeTestTags.ROOT) > 0 }
        compose.onNodeWithTag(HomeTestTags.ROOT).assertExists()
    }
}

private fun ComposeTestRule.count(tag: String): Int = onAllNodesWithTag(tag).fetchSemanticsNodes().size
