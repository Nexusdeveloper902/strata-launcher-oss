package app.strata.launcher.pin

import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.strata.launcher.appGraph
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** T-SEC-02 (level I): a forged pin request (no `PinItemRequest`) finishes immediately, with no UI and no state. */
@RunWith(AndroidJUnit4::class)
class PinRequestDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun tSec02_forgedPinRequestFinishesImmediately() {
        val graph = context.applicationContext.appGraph
        val before = runBlocking { graph.settingsRepository.settings.first() to graph.favorites.favorites.first() }
        for (action in listOf(LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT, LauncherApps.ACTION_CONFIRM_PIN_APPWIDGET)) {
            val forged =
                Intent(action)
                    .setComponent(ComponentName(context, PinRequestActivity::class.java))
                    .putExtra(LauncherApps.EXTRA_PIN_ITEM_REQUEST, "forged")
            val started = System.nanoTime()
            ActivityScenario.launch<PinRequestActivity>(forged).use { scenario ->
                assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
            }
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(5_000L)
        }
        val after = runBlocking { graph.settingsRepository.settings.first() to graph.favorites.favorites.first() }
        assertThat(after).isEqualTo(before)
    }
}
