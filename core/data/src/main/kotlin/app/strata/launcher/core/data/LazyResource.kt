package app.strata.launcher.core.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Created once, on first use, on [dispatcher] (no disk on main, ARCHITECTURE §1 principle 4). */
class LazyResource<T : Any>(
    private val dispatcher: CoroutineDispatcher,
    private val create: () -> T,
) {
    private val mutex = Mutex()

    @Volatile private var value: T? = null

    suspend fun get(): T =
        value ?: mutex.withLock {
            value ?: withContext(dispatcher) { create() }.also { value = it }
        }
}
