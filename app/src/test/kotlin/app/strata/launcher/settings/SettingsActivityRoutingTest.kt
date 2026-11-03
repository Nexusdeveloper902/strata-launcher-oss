package app.strata.launcher.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import app.strata.launcher.core.model.Settings
import app.strata.launcher.feature.onboarding.OnboardingTestTags
import app.strata.launcher.feature.settings.SettingsTestTags
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** T-HOME-13 / T-HOME-02 entry half (Robolectric): SettingsActivity as the D-008 router. */
@RunWith(AndroidJUnit4::class)
class SettingsActivityRoutingTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun launcherIntent() =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(context, SettingsActivity::class.java))

    private fun start(intent: Intent): SettingsActivity {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    @Test
    fun tHome13_notOnboarded_showsOnboardingAndStays() {
        TestAppGraph(onboarded = false).install()
        val activity = start(launcherIntent())
        compose.onNodeWithTag(OnboardingTestTags.ROOT).assertExists()
        assertThat(activity.isFinishing).isFalse()
        assertThat(shadowOf(activity).nextStartedActivity).isNull()
    }

    @Test
    fun tHome13_tHome02_onboarded_startsHomeExplicitlyAndFinishes() {
        TestAppGraph(onboarded = true).install()
        val activity = start(launcherIntent())
        val started = shadowOf(activity).nextStartedActivity
        assertThat(started.component).isEqualTo(ComponentName(context, HomeActivity::class.java))
        assertThat(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(started.categories).isNull() // explicit intent, no HOME/LAUNCHER filter needed
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun tHome13_applicationPreferences_opensSettingsRoot() {
        TestAppGraph(onboarded = false).install()
        val intent =
            Intent(Intent.ACTION_APPLICATION_PREFERENCES)
                .setComponent(ComponentName(context, SettingsActivity::class.java))
        val activity = start(intent)
        compose.onNodeWithTag(SettingsTestTags.ROOT).assertExists()
        compose.onNodeWithTag(OnboardingTestTags.ROOT).assertDoesNotExist()
        assertThat(activity.isFinishing).isFalse()
        assertThat(shadowOf(activity).nextStartedActivity).isNull()
    }

    @Test
    fun onboardingContinue_setsFlagThenOpensHome() {
        val store = FakeSettingsRepository(Settings(onboardingDone = false))
        TestAppGraph(settingsRepository = store).install()
        val activity = start(launcherIntent())
        compose.onNodeWithTag(OnboardingTestTags.CONTINUE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(store.value.onboardingDone).isTrue()
        assertThat(shadowOf(activity).nextStartedActivity.component)
            .isEqualTo(ComponentName(context, HomeActivity::class.java))
        assertThat(activity.isFinishing).isTrue()
    }
}
