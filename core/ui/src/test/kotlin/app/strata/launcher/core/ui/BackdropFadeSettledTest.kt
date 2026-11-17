package app.strata.launcher.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-PERF-13 (battery): [BackdropFade.settled] is the signal home waits for before it drops the wallpaper. It follows
 * the running animation, so a system animator scale (here 3×) stretches it; a fixed delay of the base duration would
 * drop the wallpaper a third of the way into the fade.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class BackdropFadeSettledTest {
    @get:Rule
    val compose =
        createComposeRule(
            effectContext =
                object : MotionDurationScale {
                    override val scaleFactor: Float = 3f
                },
        )

    @Test
    fun tPerf13_settledFollowsTheScaledFade() {
        val a = Backdrop(Color.Black, 0.5f, Color.Black, 0f)
        val b = Backdrop(Color.Black, 0f, Color.White, 1f)
        var target by mutableStateOf(a)
        lateinit var fade: BackdropFade
        compose.mainClock.autoAdvance = false
        compose.setContent { fade = rememberBackdropFade(target, crossFade = true, durationMs = BASE_MS) }
        compose.mainClock.advanceTimeByFrame()
        assertThat(fade.settled).isTrue()
        compose.runOnUiThread { target = b }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertThat(fade.settled).isFalse()
        compose.mainClock.advanceTimeBy(BASE_MS + 100L) // past the unscaled duration: still fading
        assertThat(fade.settled).isFalse()
        compose.mainClock.advanceTimeBy(3L * BASE_MS)
        assertThat(fade.settled).isTrue()
    }

    private companion object {
        const val BASE_MS = 300
    }
}
