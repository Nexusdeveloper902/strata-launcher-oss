package app.strata.launcher.pin

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.strata.launcher.appGraph
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T-SEC-02 (level I): a forged pin request (no `PinItemRequest`) "finishes immediately. No UI, no state". Measured on
 * the activity itself: it is already finishing when `onCreate` returns, never reaches STARTED or RESUMED (no window is
 * shown), and is destroyed; settings and favourites are unchanged. No wall-clock budget: the time an emulator takes to
 * start and tear down an activity is not the activity's behaviour (CI run 41930665678, API 34: DESTROYED 130 ms after
 * the start, but `ActivityScenario.close()` then waited 5 s for its own teardown).
 */
@RunWith(AndroidJUnit4::class)
class PinRequestDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun tSec02_forgedPinRequestFinishesImmediately() {
        val graph = context.applicationContext.appGraph
        val before = runBlocking { graph.settingsRepository.settings.first() to graph.favorites.favorites.first() }
        for (action in listOf(LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT, LauncherApps.ACTION_CONFIRM_PIN_APPWIDGET)) {
            val stages = CopyOnWriteArrayList<Stage>()
            var finishingAfterCreate: Boolean? = null
            val destroyed = CountDownLatch(1)
            val callback =
                ActivityLifecycleCallback { activity: Activity, stage: Stage ->
                    if (activity !is PinRequestActivity) return@ActivityLifecycleCallback
                    stages += stage
                    if (stage == Stage.CREATED) finishingAfterCreate = activity.isFinishing
                    if (stage == Stage.DESTROYED) destroyed.countDown()
                }
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            monitor.addLifecycleCallback(callback)
            try {
                context.startActivity(
                    Intent(action)
                        .setComponent(ComponentName(context, PinRequestActivity::class.java))
                        .putExtra(LauncherApps.EXTRA_PIN_ITEM_REQUEST, "forged")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                // A wait for the event, not a performance budget.
                assertThat(destroyed.await(DESTROY_WAIT_S, TimeUnit.SECONDS)).isTrue()
            } finally {
                monitor.removeLifecycleCallback(callback)
            }
            assertThat(finishingAfterCreate).isTrue()
            assertThat(stages).containsNoneOf(Stage.STARTED, Stage.RESUMED)
            assertThat(stages.last()).isEqualTo(Stage.DESTROYED)
        }
        val after = runBlocking { graph.settingsRepository.settings.first() to graph.favorites.favorites.first() }
        assertThat(after).isEqualTo(before)
    }

    private companion object {
        const val DESTROY_WAIT_S = 30L
    }
}
