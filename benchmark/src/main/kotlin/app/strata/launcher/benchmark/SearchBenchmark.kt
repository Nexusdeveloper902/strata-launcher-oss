package app.strata.launcher.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.strata.launcher.testing.fixtures.perf.PerfBudgets
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * Search latency (NFR-PERF-03). Every ranking pass is a `strata.searchRank` slice (SearchViewModel), every contact query
 * a `strata.contactsQuery` slice (AndroidContactsSource); [SliceDurationsMetric] reports each one so the percentiles
 * are over single passes, as the budget is written. Budgets are checked on a physical device only ([BudgetGate]).
 */
@RunWith(AndroidJUnit4::class)
class SearchBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @get:Rule
    val test = TestName()

    private val queries = listOf("a", "ma", "map", "mpas", "テ")

    /** T-PERF-05 (device half): the 5 queries ×100 (10 iterations × 10 rounds); ranking ≤ 8 ms median, ≤ 16 ms p95. */
    @Test
    fun tPerf05_searchTyping() {
        rule.measureRepeated(
            packageName = Strata.TARGET,
            metrics = listOf(SliceDurationsMetric(RANK), FrameTimingMetric()),
            iterations = 10,
            setupBlock = { openSearch() },
        ) {
            val field = device.findOrFail(Strata.SEARCH_FIELD)
            repeat(10) {
                for (q in queries) {
                    field.text = q
                    device.waitForIdle()
                }
            }
        }
        BudgetGate.check(
            javaClass,
            test.methodName,
            BudgetGate.medianAtMost(SliceDurationsMetric.name(RANK), PerfBudgets.SEARCH_RANK_MEDIAN_MS, "T-PERF-05"),
            BudgetGate.percentileAtMost(
                SliceDurationsMetric.name(RANK),
                95.0,
                PerfBudgets.SEARCH_RANK_P95_MS,
                "T-PERF-05",
            ),
        )
    }

    /**
     * T-PERF-06: `CONTACTS2000` on the device (tools/perf/contacts2000.sh) and contact search switched on in the
     * benchmark app (Settings -> Search -> Contacts in search, permission granted). 5 queries ×20; contact results ≤ 50 ms p95
     * and app ranking still within T-PERF-05 (apps are not delayed by contacts).
     */
    @Test
    fun tPerf06_searchWithContacts() {
        val shell = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Only the fixture account counts (tools/perf/contacts2000.sh), never the owner's own contacts. The command is
        // not run through a shell, so the where clause must not contain spaces.
        val contacts =
            shell
                .executeShellCommand(
                    "content query --uri content://com.android.contacts/raw_contacts --projection _id " +
                        "--where account_name='$FIXTURE_ACCOUNT'AND(deleted=0)",
                ).lines()
                .count { it.startsWith("Row:") }
        BudgetGate.log("T-PERF-06: $contacts CONTACTS2000 fixture contacts on the device")
        // Emulator (CI): no fixture contacts, nothing to measure. On a device a missing fixture is a failed precondition.
        assumeTrue("no CONTACTS2000 fixture on this emulator", contacts > 0 || BudgetGate.enforced)
        check(contacts >= CONTACTS2000) {
            "T-PERF-06 needs CONTACTS2000: run tools/perf/contacts2000.sh load ($contacts found)"
        }
        rule.measureRepeated(
            packageName = Strata.TARGET,
            metrics = listOf(SliceDurationsMetric(CONTACTS), SliceDurationsMetric(RANK)),
            iterations = 4,
            setupBlock = {
                val field = openSearch()
                field.text = "a"
                check(device.wait(Until.hasObject(By.res(Strata.CONTACTS_HEADER)), Strata.TIMEOUT_MS) == true) {
                    "T-PERF-06: no contact results. Switch contact search on in the benchmark app (its Settings -> " +
                        "Search -> Contacts in search, allow the permission) first"
                }
            },
        ) {
            val field = device.findOrFail(Strata.SEARCH_FIELD)
            repeat(5) {
                for (q in queries) {
                    field.text = q
                    device.waitForIdle()
                }
            }
        }
        BudgetGate.check(
            javaClass,
            test.methodName,
            BudgetGate.percentileAtMost(
                SliceDurationsMetric.name(CONTACTS),
                95.0,
                PerfBudgets.SEARCH_CONTACTS_P95_MS,
                "T-PERF-06",
            ),
            BudgetGate.percentileAtMost(
                SliceDurationsMetric.name(RANK),
                95.0,
                PerfBudgets.SEARCH_RANK_P95_MS,
                "T-PERF-06",
            ),
        )
    }

    private companion object {
        const val RANK = "strata.searchRank"
        const val CONTACTS = "strata.contactsQuery"
        const val CONTACTS2000 = 2_000
        const val FIXTURE_ACCOUNT = "strata-fixture"
    }
}
