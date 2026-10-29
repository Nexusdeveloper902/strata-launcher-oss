package app.strata.launcher.testing.fakes

import app.strata.launcher.core.common.Clock

/** Manually advanced [Clock]. */
class FakeClock(var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now

    fun advanceBy(millis: Long) {
        now += millis
    }
}
