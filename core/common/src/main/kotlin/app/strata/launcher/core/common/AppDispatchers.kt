package app.strata.launcher.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Dispatchers injected everywhere so tests can replace them (ARCHITECTURE §6). */
data class AppDispatchers(
    val main: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val default: CoroutineDispatcher,
) {
    companion object {
        fun standard(): AppDispatchers = AppDispatchers(
            main = Dispatchers.Main,
            io = Dispatchers.IO,
            default = Dispatchers.Default,
        )
    }
}
