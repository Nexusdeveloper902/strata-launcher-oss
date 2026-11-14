package app.strata.launcher.home

import android.animation.ValueAnimator
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.appGraph
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.SheetTestTags
import app.strata.launcher.feature.search.SearchTestTags
import app.strata.launcher.openAllApps
import app.strata.launcher.passOnboarding
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * T-A11Y-12 (I half), NFR-A11Y-05: with the system animation scales at 0 ("Remove animations"; [DeviceStateRule] sets
 * them before the activity starts and restores them after), the resolved theme has animations off, so every Strata
 * transition is a cut; search, the list and the sheet open and close. The frame-by-frame "no movement" check is the
 * C half (`ReducedMotionTest`).
 */
@RunWith(AndroidJUnit4::class)
class ReducedMotionDeviceTest {
    val compose = createAndroidComposeRule<HomeActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(DeviceStateRule()).around(compose)

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun graph() =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext.applicationContext.appGraph

    @Before
    fun ready() {
        compose.waitForIdle()
        compose.passOnboarding()
        compose.waitUntil(timeoutMillis = 10_000) { count(HomeTestTags.ROOT) > 0 }
    }

    @Test
    fun tA11y12_removeAnimations_cutsOnly() {
        assertThat(ValueAnimator.areAnimatorsEnabled()).isFalse()
        // The theme inputs read the animator scale; the resolved theme then has animations off (FR-THEME-16).
        compose.waitUntil(timeoutMillis = 10_000) {
            !graph()
                .themeRepository.state.value.themes.light.animation.enabled
        }
        compose.onNodeWithTag(HomeTestTags.PILL).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { count(SearchTestTags.LAYER) > 0 }
        device.pressBack()
        device.pressBack()
        compose.waitUntil(timeoutMillis = 10_000) { count(SearchTestTags.LAYER) == 0 }
        compose.openAllApps()
        val row =
            SemanticsMatcher("an app row") { n ->
                n.config.getOrElseNullable(SemanticsProperties.TestTag) { null }?.startsWith("app_row_") == true &&
                    n.config.contains(SemanticsActions.OnLongClick)
            }
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(row)[0].performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitUntil(timeoutMillis = 10_000) { count(SheetTestTags.SHEET) > 0 }
        device.pressBack()
        compose.waitUntil(timeoutMillis = 10_000) { count(SheetTestTags.SHEET) == 0 }
    }
}
