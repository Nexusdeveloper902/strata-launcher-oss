package app.strata.launcher.testing.fixtures.perf

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.test.assertFailsWith

/** The arithmetic of the M9 device budget gate (the budgets are asserted only on a device). */
class BudgetMathTest {
    private val ten = (1..10).map { it.toDouble() }

    @Test
    fun `nearest-rank percentiles`() {
        assertThat(BudgetMath.median(ten)).isEqualTo(5.0)
        assertThat(BudgetMath.percentile(ten, 90.0)).isEqualTo(9.0)
        assertThat(BudgetMath.percentile(ten, 95.0)).isEqualTo(10.0)
        assertThat(BudgetMath.percentile(listOf(3.0, 1.0, 2.0), 50.0)).isEqualTo(2.0)
        assertThat(BudgetMath.percentile(listOf(7.0), 99.0)).isEqualTo(7.0)
    }

    @Test
    fun `percentile needs samples and a valid p`() {
        assertFailsWith<IllegalArgumentException> { BudgetMath.median(emptyList()) }
        assertFailsWith<IllegalArgumentException> { BudgetMath.percentile(ten, 0.0) }
    }

    @Test
    fun `jank counts frames that overran their deadline`() {
        assertThat(BudgetMath.jankFraction(listOf(-3.0, 0.0, 1.5, -10.0))).isEqualTo(0.25)
        assertThat(BudgetMath.jankFraction(List(100) { -1.0 })).isEqualTo(0.0)
    }

    @Test
    fun `a long frame is long by CPU time or by deadline plus overrun`() {
        val cpu = listOf(10.0, 55.0, 12.0, 20.0)
        val overrun = listOf(-5.0, 2.0, 40.0, 30.0)
        // 60 Hz: 16.7 + 40 = 56.7 > 50; 16.7 + 30 = 46.7 is not.
        assertThat(BudgetMath.framesLongerThan(cpu, overrun, 1000.0 / 60, 50.0)).isEqualTo(2)
    }
}
