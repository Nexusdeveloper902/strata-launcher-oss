package app.strata.launcher

import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.strata.launcher.feature.onboarding.OnboardingTestTags
import org.robolectric.Shadows.shadowOf

/**
 * Walks the first-run flow without changes (T-ONBOARD-01 path): Welcome → Skip favourites → set-default step →
 * Done. While Strata is the home app the step shows "Strata is your home app" and Continue; otherwise "Not now" ends
 * the flow. Stops when [finished] (e.g. `onboarding_done` stored) or the flow is gone; returns the screens seen.
 */
fun ComposeTestRule.walkOnboarding(finished: () -> Boolean = { false }): Int {
    fun has(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    fun click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }
    var screens = 0
    while (!finished() && has(OnboardingTestTags.ROOT) && screens < MAX_SCREENS) {
        screens++
        when {
            has(OnboardingTestTags.WELCOME) -> click(OnboardingTestTags.CONTINUE)
            has(OnboardingTestTags.SKIP) -> click(OnboardingTestTags.SKIP)
            has(OnboardingTestTags.NOT_NOW) -> click(OnboardingTestTags.NOT_NOW)
            else -> click(OnboardingTestTags.CONTINUE)
        }
    }
    return screens
}

private const val MAX_SCREENS = 6
