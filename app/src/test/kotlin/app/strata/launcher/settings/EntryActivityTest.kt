package app.strata.launcher.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import app.strata.launcher.home.HomeActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl

/**
 * T-HOME-17 (FB-019, Robolectric half): the app icon's entry is window-less. It is declared with `Theme.NoDisplay` and
 * the `MAIN` + `LAUNCHER` filter (and `SettingsActivity` no longer has it); in `onCreate` it sets no content, adds no
 * window, starts the D-008 target and finishes before `onResume`.
 */
@RunWith(AndroidJUnit4::class)
class EntryActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun launcherIntent() =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(context, EntryActivity::class.java))

    private fun created(): EntryActivity =
        Robolectric.buildActivity(EntryActivity::class.java, launcherIntent()).create().get()

    /**
     * Finished in `onCreate`, no content set, and nothing added to the window manager: the decor view (which the
     * framework may create lazily) has no view root and is not attached, and the window manager holds no view of ours.
     * The platform only adds an activity's window after `onResume` of an activity that is not finishing.
     */
    private fun assertWindowLess(activity: EntryActivity) {
        assertThat(activity.isFinishing).isTrue()
        val content = activity.window.peekDecorView()?.findViewById<ViewGroup>(android.R.id.content)
        assertThat(content?.childCount ?: 0).isEqualTo(0)
        assertThat(activity.window.peekDecorView()?.parent).isNull()
        assertThat(activity.window.peekDecorView()?.isAttachedToWindow ?: false).isFalse()
        assertThat(Shadow.extract<ShadowWindowManagerImpl>(activity.windowManager).views).isEmpty()
    }

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
    fun tHome17_onboarded_startsHomeInItsTaskWithoutAWindow() {
        TestAppGraph(onboarded = true).install()
        val activity = created()
        val started = shadowOf(activity).nextStartedActivity
        assertThat(started.component).isEqualTo(ComponentName(context, HomeActivity::class.java))
        assertThat(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(started.categories).isNull()
        assertWindowLess(activity)
    }

    @Test
    fun tHome17_notOnboarded_handsTheLauncherIntentToSettingsInThisTask() {
        TestAppGraph(onboarded = false).install()
        val activity = created()
        val started = shadowOf(activity).nextStartedActivity
        assertThat(started.component).isEqualTo(ComponentName(context, SettingsActivity::class.java))
        assertThat(started.action).isEqualTo(Intent.ACTION_MAIN)
        assertThat(started.categories).containsExactly(Intent.CATEGORY_LAUNCHER)
        assertThat(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isEqualTo(0)
        // SettingsActivity classifies the hand-over as the launcher icon: it routes (onboarding), not settings root.
        assertThat(EntryRouter.classify(started.action, started.categories)).isEqualTo(EntryKind.LAUNCHER_ICON)
        assertWindowLess(activity)
    }

    @Test
    fun tHome17_unknownFlagLeavesTheDecisionToTheRouter() {
        assertThat(EntryRouter.entryTarget(null)).isEqualTo(EntryTarget.ROUTER)
        assertThat(EntryRouter.entryTarget(false)).isEqualTo(EntryTarget.ROUTER)
        assertThat(EntryRouter.entryTarget(true)).isEqualTo(EntryTarget.HOME)
    }
}
