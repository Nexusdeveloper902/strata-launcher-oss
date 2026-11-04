package app.strata.launcher.pin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/** T-SEC-02 (R half): PinRequestActivity with forged intents finishes at once, with no UI and no state. */
@RunWith(AndroidJUnit4::class)
class PinRequestActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun forged(action: String?) =
        Intent(action).setComponent(ComponentName(context, PinRequestActivity::class.java))

    @Test
    fun tSec02_forgedIntentsFinishWithoutUi() {
        val graph = TestAppGraph(onboarded = true).install()
        val intents =
            listOf(
                forged(LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT),
                forged(LauncherApps.ACTION_CONFIRM_PIN_APPWIDGET),
                forged(LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT)
                    .putExtra(LauncherApps.EXTRA_PIN_ITEM_REQUEST, "not a request")
                    .putExtra(Intent.EXTRA_INTENT, Intent(Intent.ACTION_VIEW)),
                forged(null),
                forged(Intent.ACTION_VIEW),
            )
        for (intent in intents) {
            val activity = Robolectric.buildActivity(PinRequestActivity::class.java, intent).setup().get()
            assertThat(activity.isFinishing).isTrue()
            assertThat(shadowOf(activity).nextStartedActivity).isNull()
            val content = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            assertThat(content?.childCount ?: 0).isEqualTo(0)
        }
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
        assertThat(graph.favorites.state.value).isEmpty()
        assertThat(graph.settingsRepository.value).isEqualTo(TestAppGraph(onboarded = true).settingsRepository.value)
    }
}
