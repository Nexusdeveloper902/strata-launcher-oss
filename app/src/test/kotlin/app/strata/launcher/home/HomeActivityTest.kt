package app.strata.launcher.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import app.strata.launcher.core.model.Settings
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.onboarding.OnboardingTestTags
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** HomeActivity shell (Robolectric). Device-level halves of these tests run on the phone (level I). */
@RunWith(AndroidJUnit4::class)
class HomeActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun homeIntent() =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setComponent(ComponentName(context, HomeActivity::class.java))

    private fun start(intent: Intent = homeIntent()): HomeActivity {
        val activity = Robolectric.buildActivity(HomeActivity::class.java, intent).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    /** T-HOME-01 (M0: placeholder shown) — and no banner while Strata is the default home. */
    @Test
    fun tHome01_placeholderShown_noBannerWhenDefault() {
        TestAppGraph(
            roleController = FakeRoleController(defaultHome = true),
            onboarded = true,
        ).install()
        start()
        compose.onNodeWithTag(HomeTestTags.ROOT).assertExists()
        compose.onNodeWithTag(HomeTestTags.APP_MODE_BANNER).assertDoesNotExist()
    }

    /** T-HOME-02 (banner stub): APP_MODE banner when the role is not held; the debug button is present. */
    @Test
    fun tHome02_appModeBannerWhenNotDefault() {
        TestAppGraph(
            roleController = FakeRoleController(defaultHome = false),
            onboarded = true,
        ).install()
        start()
        compose.onNodeWithTag(HomeTestTags.ROOT).assertExists()
        compose.onNodeWithTag(HomeTestTags.APP_MODE_BANNER).assertExists()
        compose.onNodeWithTag(HomeTestTags.SET_DEFAULT_BUTTON).assertExists()
    }

    /** FR-ONBOARD-04 groundwork: the role is re-checked on every resume. */
    @Test
    fun bannerDisappearsAfterRoleGrantedOnResume() {
        val role = FakeRoleController(defaultHome = false)
        TestAppGraph(roleController = role, onboarded = true).install()
        val controller = Robolectric.buildActivity(HomeActivity::class.java, homeIntent()).setup()
        shadowOf(Looper.getMainLooper()).idle()
        compose.onNodeWithTag(HomeTestTags.APP_MODE_BANNER).assertExists()
        role.defaultHome = true
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        compose.onNodeWithTag(HomeTestTags.APP_MODE_BANNER).assertDoesNotExist()
    }

    /** T-SEC-03 (Robolectric half): arbitrary extras have no effect beyond showing home. */
    @Test
    fun tSec03_extrasIgnored() {
        TestAppGraph(
            roleController = FakeRoleController(defaultHome = true),
            onboarded = true,
        ).install()
        val hostile =
            homeIntent()
                .putExtra(Intent.EXTRA_INTENT, Intent(Intent.ACTION_VIEW, Uri.parse("content://evil/x")))
                .putExtra("component", "com.example/.Evil")
                .putExtra("uri", "https://example.com")
        val activity = start(hostile)
        compose.onNodeWithTag(HomeTestTags.ROOT).assertExists()
        assertThat(shadowOf(activity).nextStartedActivity).isNull()
        assertThat(activity.isFinishing).isFalse()
    }

    /** D-008 / T-ONBOARD-09 (stub): onboarding is home's first layer until Continue. */
    @Test
    fun onboardingLayerShownUntilContinue() {
        val store = FakeSettingsRepository(Settings(onboardingDone = false))
        TestAppGraph(roleController = FakeRoleController(defaultHome = true), settingsRepository = store).install()
        start()
        compose.onNodeWithTag(OnboardingTestTags.ROOT).assertExists()
        compose.onNodeWithTag(OnboardingTestTags.CONTINUE).performClick()
        compose.waitForIdle()
        assertThat(store.value.onboardingDone).isTrue()
        compose.onNodeWithTag(OnboardingTestTags.ROOT).assertDoesNotExist()
        compose.onNodeWithTag(HomeTestTags.ROOT).assertExists()
    }
}
