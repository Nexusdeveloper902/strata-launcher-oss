package app.strata.launcher.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M1 macrobenchmarks on RD-1 with `APPS500` installed (nightly, report only until M9; IMPLEMENTATION_ROADMAP M1).
 * Run with `./gradlew :benchmark:connectedBenchmarkAndroidTest`. Never run in this repo's container.
 */
@RunWith(AndroidJUnit4::class)
class ListBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private fun MacrobenchmarkScope.openList() {
        startActivityAndWait()
        device.findObject(By.res(ALL_APPS))?.click() ?: device.findObject(By.text("All apps ›"))?.click()
        device.wait(Until.hasObject(By.res(LIST)), TIMEOUT_MS)
    }

    /** T-PERF-01: cold start to fully drawn (reportFullyDrawn once the list snapshot is composed). */
    @Test
    fun tPerf01_coldStart() =
        rule.measureRepeated(
            packageName = TARGET,
            metrics = listOf(StartupTimingMetric()),
            iterations = 10,
            startupMode = StartupMode.COLD,
        ) {
            pressHome()
            startActivityAndWait()
        }

    /** T-PERF-03: 5 flings top↔bottom; janky frames ≤ 1 %, no frame > 50 ms (FrameTimingMetric). */
    @Test
    fun tPerf03_listFling() =
        rule.measureRepeated(
            packageName = TARGET,
            metrics = listOf(FrameTimingMetric()),
            iterations = 5,
            setupBlock = { openList() },
        ) {
            val list = device.findObject(By.res(LIST)) ?: return@measureRepeated
            list.setGestureMargin(device.displayWidth / 5)
            repeat(5) { i -> list.fling(if (i % 2 == 0) Direction.DOWN else Direction.UP) }
        }

    /** T-PERF-04: full A→Z index scrub ×3; janky ≤ 3 %. */
    @Test
    fun tPerf04_indexScrub() =
        rule.measureRepeated(
            packageName = TARGET,
            metrics = listOf(FrameTimingMetric()),
            iterations = 3,
            setupBlock = { openList() },
        ) {
            val index = device.findObject(By.res(INDEX)) ?: return@measureRepeated
            val b = index.visibleBounds
            device.swipe(b.centerX(), b.top + b.height() / 10, b.centerX(), b.bottom - b.height() / 10, 120)
        }

    /** T-PERF-10: install/remove the fixture ×10; row composed ≤ 1 s after `onPackageAdded` (trace markers). */
    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun tPerf10_changePropagation() {
        assumeTrue(java.io.File(FIXTURE_APK).exists())
        rule.measureRepeated(
            packageName = TARGET,
            metrics =
                listOf(
                    TraceSectionMetric("strata.onPackageAdded", TraceSectionMetric.Mode.First),
                    TraceSectionMetric("strata.rowComposed", TraceSectionMetric.Mode.First),
                ),
            iterations = 10,
            setupBlock = {
                device.executeShellCommand("pm uninstall $FIXTURE_PACKAGE")
                openList()
            },
        ) {
            val started = System.nanoTime()
            device.executeShellCommand("pm install -r $FIXTURE_APK")
            val list = device.findObject(By.res(LIST))
            list?.scrollUntil(Direction.DOWN, Until.findObject(By.text("Zz Test")))
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue("row after $ms ms", device.hasObject(By.text("Zz Test")))
            device.executeShellCommand("pm uninstall $FIXTURE_PACKAGE")
        }
    }

    private companion object {
        const val TARGET = "app.strata.launcher"
        const val ALL_APPS = "home_all_apps"
        const val LIST = "app_list"
        const val INDEX = "alphabet_index"
        const val TIMEOUT_MS = 5_000L
        const val FIXTURE_PACKAGE = "app.strata.fixture.zz"
        const val FIXTURE_APK = "/data/local/tmp/strata-fixture-zz.apk"
    }
}
