package app.strata.launcher.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-PERF-05 (device half, report only): with `APPS500` installed, type "a", "ma", "map", "mpas" into search
 * and read the `strata.searchRank` trace sections (≤ 8 ms median, ≤ 16 ms p95) and frame timing.
 * Run with `./gradlew :benchmark:connectedBenchmarkAndroidTest`. Never run in this repo's container.
 */
@RunWith(AndroidJUnit4::class)
class SearchBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun tPerf05_searchTyping() =
        rule.measureRepeated(
            packageName = TARGET,
            metrics =
                listOf(
                    TraceSectionMetric("strata.searchRank", TraceSectionMetric.Mode.Average),
                    TraceSectionMetric("strata.searchRank", TraceSectionMetric.Mode.Max),
                    FrameTimingMetric(),
                ),
            iterations = 10,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                device.findObject(By.res(ALL_APPS))?.click()
                device.wait(Until.hasObject(By.res(SEARCH_FIELD_IN_LIST)), TIMEOUT_MS)
                device.findObject(By.res(SEARCH_FIELD_IN_LIST))?.click()
                device.wait(Until.hasObject(By.res(FIELD)), TIMEOUT_MS)
            },
        ) {
            val field = device.findObject(By.res(FIELD)) ?: return@measureRepeated
            for (q in listOf("a", "ma", "map", "mpas")) {
                field.text = q
                device.waitForIdle()
            }
        }

    private companion object {
        const val TARGET = "app.strata.launcher.benchmark"
        const val ALL_APPS = "home_all_apps"
        const val SEARCH_FIELD_IN_LIST = "app_list_search_field"
        const val FIELD = "search_field"
        const val TIMEOUT_MS = 5_000L
    }
}
