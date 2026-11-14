package app.strata.launcher.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.ui.StrataTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-PERF-13 (battery), the header clock: in minutes mode it reads the time once per minute tick and never in between;
 * with seconds once a second; in both modes nothing while the activity is not resumed (screen off, app on top).
 */
@RunWith(AndroidJUnit4::class)
class ClockTickTest {
    @get:Rule
    val compose = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
    private val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private var reads = 0

    /** Wall time that follows the test clock, from 0.5 s into a minute; each call is one time read (one tick). */
    private val now: () -> Long = {
        reads++
        BASE_MILLIS + compose.mainClock.currentTime
    }

    private fun show(seconds: Boolean) {
        val base = StrataDefault.resolved(systemDark = false)
        val theme = base.copy(clock = base.clock.copy(showSeconds = seconds))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                StrataTheme(theme) {
                    HomeHeader(ticks = ticks.onStart { emit(Unit) }, onClock = {}, onDate = {}, now = now)
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @Test
    fun tPerf13_minutesClockReadsTimeOnlyOnTicks() {
        show(seconds = false)
        val start = reads
        compose.mainClock.advanceTimeBy(10 * 60_000L)
        assertThat(reads - start).isEqualTo(0)
        ticks.tryEmit(Unit)
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(reads - start).isEqualTo(1)
    }

    @Test
    fun tPerf13_secondsClockTicksOncePerSecond() {
        show(seconds = true)
        val start = reads
        compose.mainClock.advanceTimeBy(10_000)
        // About one read per second (alignment to the second may add one), never one per frame.
        assertThat(reads - start).isIn(9..11)
    }

    @Test
    fun tPerf13_clockStopsWhenNotResumed() {
        show(seconds = true)
        owner.registry.currentState = Lifecycle.State.CREATED // stopped: screen off or another app on top
        compose.mainClock.advanceTimeBy(1_000)
        val start = reads
        compose.mainClock.advanceTimeBy(60_000)
        ticks.tryEmit(Unit)
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(reads - start).isEqualTo(0)
        owner.registry.currentState = Lifecycle.State.RESUMED
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(reads - start).isAtLeast(1)
    }

    private companion object {
        const val BASE_MILLIS = 1_767_225_600_500L
    }
}
