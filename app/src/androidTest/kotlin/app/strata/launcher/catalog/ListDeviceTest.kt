package app.strata.launcher.catalog

import android.graphics.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.onboarding.OnboardingTestTags
import app.strata.launcher.home.HomeActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Level I/M tests that install, disable and launch the "Zz Test" fixture APK. The fixture (package
 * [FIXTURE_PACKAGE], label "Zz Test", writes its `intent.sourceBounds` to its own log) is not built yet: push it
 * to [FIXTURE_APK] first. Without it every test here is skipped (assumption), never failed.
 */
@RunWith(AndroidJUnit4::class)
class ListDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<HomeActivity>()

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun shell(cmd: String): String = device.executeShellCommand(cmd)

    private fun installed(): Boolean = shell("pm path $FIXTURE_PACKAGE").contains("package:")

    private fun openList() {
        compose.waitForIdle()
        if (compose.onAllNodesWithTag(OnboardingTestTags.CONTINUE).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag(OnboardingTestTags.CONTINUE).performClick()
        }
        compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
    }

    private fun waitForRow(
        present: Boolean,
        timeoutMs: Long = 1_000,
    ) = compose.waitUntil(timeoutMs) {
        compose.onAllNodesWithText(FIXTURE_LABEL).fetchSemanticsNodes().isNotEmpty() == present
    }

    private fun scrollToFixture() =
        compose
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasText(FIXTURE_LABEL))

    /** T-LIST-08 / NFR-PERF-06: install → row ≤ 1 s; uninstall → removed ≤ 1 s; no restart. */
    @Test
    fun tList08_liveInstallAndRemove() {
        assumeTrue(File(FIXTURE_APK).exists() || shell("ls $FIXTURE_APK").contains(FIXTURE_APK))
        shell("pm uninstall $FIXTURE_PACKAGE")
        openList()
        shell("pm install -r $FIXTURE_APK")
        waitForRow(present = true)
        shell("pm uninstall $FIXTURE_PACKAGE")
        waitForRow(present = false)
    }

    /** T-APPS-02: the fixture starts with sourceBounds equal to the row bounds (±2 px). */
    @Test
    fun tApps02_launchWithSourceBounds() {
        assumeTrue(installed())
        openList()
        scrollToFixture()
        val node = compose.onNodeWithText(FIXTURE_LABEL).fetchSemanticsNode()
        val row = node.boundsInWindow
        compose.onNodeWithText(FIXTURE_LABEL).performClick()
        device.waitForIdle()
        val reported = Rect.unflattenFromString(shell("run-as $FIXTURE_PACKAGE cat files/source_bounds.txt").trim())
        assumeTrue(reported != null)
        val location = IntArray(2)
        compose.activity.window.decorView
            .getLocationOnScreen(location)
        assertThat(reported!!.left.toFloat()).isWithin(2f).of(row.left + location[0])
        assertThat(reported.top.toFloat()).isWithin(2f).of(row.top + location[1])
    }

    /** T-APPS-03: a disabled activity → "Couldn't open …"; reconcile removes the row; no crash. */
    @Test
    fun tApps03_launchFailureAfterDisable() {
        assumeTrue(installed())
        openList()
        scrollToFixture()
        shell("pm disable-user --user 0 $FIXTURE_PACKAGE") // callbacks may remove the row first; then nothing to tap
        if (compose.onAllNodesWithText(FIXTURE_LABEL).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText(FIXTURE_LABEL).performClick()
        }
        waitForRow(present = false, timeoutMs = 3_000)
        shell("pm enable --user 0 $FIXTURE_PACKAGE")
    }

    private companion object {
        const val FIXTURE_PACKAGE = "app.strata.fixture.zz"
        const val FIXTURE_LABEL = "Zz Test"
        const val FIXTURE_APK = "/data/local/tmp/strata-fixture-zz.apk"
    }
}
