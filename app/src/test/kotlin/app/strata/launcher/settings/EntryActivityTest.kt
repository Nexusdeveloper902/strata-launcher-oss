package app.strata.launcher.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.onboarding.OnboardingTestTags
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.home.HomeIntents
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * T-HOME-17 (FB-019, Robolectric half): the app icon's entry is window-less and reads nothing.
 * - Declared with `Theme.NoDisplay`, the only `MAIN` + `LAUNCHER` activity of the package.
 * - Driven through `resume()`: the framework's own check (`Activity.performResume` throws "did not call finish() prior
 *   to onResume() completing" for a no-display activity that is still running) does not fire, and it is finishing.
 *   `EntryActivity` does not touch the app graph or any store (no slow or failing read can delay or crash the tap).
 * - It starts [HomeActivity] explicitly with [HomeIntents.ACTION_ENTRY] in a new task. Home then routes: a first run
 *   shows onboarding as its first layer and Back on Welcome moves the task back (to the launcher); onboarded, home.
 */
@RunWith(AndroidJUnit4::class)
class EntryActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun launcherIntent() =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(context, EntryActivity::class.java))

    @Test
    fun tHome17_declaredWindowLessWithTheLauncherFilter() {
        val pm = context.packageManager
        val info = pm.getActivityInfo(ComponentName(context, EntryActivity::class.java), 0)
        assertThat(info.theme).isEqualTo(android.R.style.Theme_NoDisplay)
        assertThat(info.exported).isTrue()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        val entries = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL).map { it.activityInfo.name }
        assertThat(entries).containsExactly(EntryActivity::class.java.name)
    }

    @Test
    fun tHome17_finishesBeforeResumeAndStartsHome() {
        TestAppGraph(onboarded = false).install()
        // Through resume: a no-display activity that had not finished would throw here.
        val activity = Robolectric.buildActivity(EntryActivity::class.java, launcherIntent()).setup().get()
        assertThat(activity.isFinishing).isTrue()
        val started = shadowOf(activity).nextStartedActivity
        assertThat(started.component).isEqualTo(ComponentName(context, HomeActivity::class.java))
        assertThat(started.action).isEqualTo(HomeIntents.ACTION_ENTRY)
        assertThat(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(started.categories).isNull()
        assertThat(started.extras).isNull()
    }

    private fun home(intent: Intent): HomeActivity {
        val activity = Robolectric.buildActivity(HomeActivity::class.java, intent).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        return activity
    }

    @Test
    fun tHome17_firstRunFromTheIcon_onboardingInHome_backOnWelcomeReturnsToTheLauncher() {
        TestAppGraph(onboarded = false).install()
        val activity = home(EntryActivity.homeIntent(context))
        compose.onNodeWithTag(OnboardingTestTags.WELCOME).assertExists()
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        assertThat(shadowOf(activity).isTaskMovedToBack).isTrue()
    }

    @Test
    fun tHome17_firstRunAsTheHomeScreen_backOnWelcomeStays() {
        TestAppGraph(onboarded = false).install()
        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(ComponentName(context, HomeActivity::class.java))
        val activity = home(intent)
        compose.onNodeWithTag(OnboardingTestTags.WELCOME).assertExists()
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        assertThat(shadowOf(activity).isTaskMovedToBack).isFalse()
        compose.onNodeWithTag(OnboardingTestTags.WELCOME).assertExists()
    }

    @Test
    fun tHome17_onboardedFromTheIcon_showsHome() {
        TestAppGraph(onboarded = true).install()
        home(EntryActivity.homeIntent(context))
        compose.onNodeWithTag(HomeTestTags.ROOT).assertExists()
        compose.onNodeWithTag(OnboardingTestTags.ROOT).assertDoesNotExist()
    }
}
