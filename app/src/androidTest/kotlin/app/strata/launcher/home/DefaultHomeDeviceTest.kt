package app.strata.launcher.home

import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.appGraph
import app.strata.launcher.coverForeignActivities
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.passOnboarding
import app.strata.launcher.shell
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Losing the default-home role while Strata runs (FR-ONBOARD-04, level I): T-LIFE-10 and the device half of
 * T-ONBOARD-08. The 24 h half runs on a fake clock (`HomeViewModelTest.tOnboard08…`).
 */
@RunWith(AndroidJUnit4::class)
class DefaultHomeDeviceTest {
    val compose = createAndroidComposeRule<HomeActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(DeviceStateRule()).around(compose)

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = context.applicationContext.appGraph

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    /** Another installed home app (the system launcher of the image). */
    private fun otherHome(): String? =
        context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
            .firstOrNull { it != context.packageName && it != "com.android.settings" }

    /** onPause → onResume of the rule's activity, as when the user comes back from Settings. */
    private fun pauseAndResume() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    @Before
    fun ready() {
        compose.passOnboarding()
        compose.waitUntil(10_000) { count(HomeTestTags.ROOT) > 0 }
    }

    @After
    fun tearDown() {
        runBlocking { graph.settingsRepository.update { it.copy(defaultHomeBannerDismissedUntil = 0L) } }
        var owned: HomeActivity? = null
        runCatching { compose.activityRule.scenario.onActivity { owned = it } }
        compose.coverForeignActivities(HomeActivity::class.java, owned)
    }

    /**
     * T-LIFE-10 / T-ONBOARD-08: the default home changes via `cmd role` while Strata is in front → the banner shows on
     * resume; Dismiss hides it and it stays hidden on the next resume; the Home key now opens the other launcher.
     */
    @Test
    fun tLife10_tOnboard08_lostDefaultBanner() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val other = otherHome()
        assumeTrue(other != null)
        other!!
        assumeTrue(graph.roleController.isDefaultHome())
        compose.waitUntil(5_000) { count(HomeTestTags.APP_MODE_BANNER) == 0 }

        shell("cmd role add-role-holder android.app.role.HOME $other")
        compose.waitUntil(10_000) { !graph.roleController.isDefaultHome() }
        pauseAndResume()
        compose.waitUntil(5_000) { count(HomeTestTags.APP_MODE_BANNER) > 0 }

        compose.onNodeWithTag(HomeTestTags.APP_MODE_BANNER_DISMISS).performClick()
        compose.waitUntil(5_000) { count(HomeTestTags.APP_MODE_BANNER) == 0 }
        val until =
            runBlocking {
                graph.settingsRepository.settings
                    .first()
                    .defaultHomeBannerDismissedUntil
            }
        assertThat(until).isGreaterThan(System.currentTimeMillis() + 23L * 60 * 60 * 1000)
        pauseAndResume()
        assertThat(count(HomeTestTags.APP_MODE_BANNER)).isEqualTo(0)

        device.pressHome()
        assertThat(device.wait(Until.hasObject(By.pkg(other)), 10_000)).isTrue()
    }
}
