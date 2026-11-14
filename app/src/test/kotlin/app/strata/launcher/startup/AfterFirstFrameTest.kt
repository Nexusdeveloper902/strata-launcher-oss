package app.strata.launcher.startup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** ARCHITECTURE §10 step 3: housekeeping waits for home's first full frame, with a time fallback (M9 deferral). */
@OptIn(ExperimentalCoroutinesApi::class)
class AfterFirstFrameTest {
    @Test
    fun `work waits for the first frame and runs right after it`() =
        runTest {
            val gate = AfterFirstFrame(backgroundScope, fallbackMillis = 3_000)
            var ran = 0
            gate.launch { ran++ }
            advanceTimeBy(1_000)
            runCurrent()
            assertThat(ran).isEqualTo(0)
            gate.firstFrameDrawn()
            runCurrent()
            assertThat(ran).isEqualTo(1)
        }

    @Test
    fun `without a home frame the work runs after the fallback`() =
        runTest {
            val gate = AfterFirstFrame(backgroundScope, fallbackMillis = 3_000)
            var ran = false
            gate.launch { ran = true }
            advanceTimeBy(2_999)
            runCurrent()
            assertThat(ran).isFalse()
            advanceTimeBy(2)
            runCurrent()
            assertThat(ran).isTrue()
        }

    @Test
    fun `work posted after the first frame runs at once, and a second report is harmless`() =
        runTest {
            val gate = AfterFirstFrame(backgroundScope)
            gate.firstFrameDrawn()
            gate.firstFrameDrawn()
            var ran = false
            gate.launch { ran = true }
            runCurrent()
            assertThat(ran).isTrue()
        }
}
