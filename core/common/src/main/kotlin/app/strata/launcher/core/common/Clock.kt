package app.strata.launcher.core.common

/** Wall-clock source. Minute ticks for the home header arrive with M3 (ARCHITECTURE §3). */
fun interface Clock {
    /** Epoch milliseconds. */
    fun nowMillis(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
