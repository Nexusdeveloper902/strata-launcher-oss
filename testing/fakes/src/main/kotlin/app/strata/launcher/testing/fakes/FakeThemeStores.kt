package app.strata.launcher.testing.fakes

import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.store.BuiltInThemeSource
import app.strata.launcher.core.theme.store.ThemeFileStore
import app.strata.launcher.core.theme.store.ThemeIndex
import app.strata.launcher.core.theme.store.ThemeIndexStore
import app.strata.launcher.core.theme.store.ThemePair
import app.strata.launcher.core.theme.store.ThemeRepository
import app.strata.launcher.core.theme.store.ThemeState
import app.strata.launcher.core.theme.store.ThemeSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException

/** Thrown by the fakes to simulate the process dying at a chosen operation. */
class SimulatedKill : RuntimeException("simulated process death")

/**
 * In-memory [ThemeFileStore] with the AtomicFile contract (each write is all-or-nothing). Set [killAfterWrites]
 * to n to make the (n+1)-th following write or move throw [SimulatedKill] *before* it takes effect.
 */
class InMemoryThemeFileStore : ThemeFileStore {
    val files = sortedMapOf<String, ByteArray>()
    var killAfterWrites: Int = -1
    val unreadable = mutableSetOf<String>()

    private fun tick() {
        if (killAfterWrites == 0) {
            killAfterWrites = -1
            throw SimulatedKill()
        }
        if (killAfterWrites > 0) killAfterWrites--
    }

    override fun read(name: String): ByteArray? {
        if (name in unreadable) throw IOException("unreadable")
        return files[name]?.copyOf()
    }

    override fun write(
        name: String,
        bytes: ByteArray,
    ) {
        tick()
        files[name] = bytes.copyOf()
    }

    override fun delete(name: String): Boolean = files.remove(name) != null

    override fun list(dir: String): List<String> =
        files.keys
            .filter {
                it.startsWith(
                    "$dir/",
                ) && '/' !in it.removePrefix("$dir/")
            }.map { it.removePrefix("$dir/") }

    override fun move(
        from: String,
        to: String,
    ) {
        tick()
        val b = files.remove(from) ?: throw IOException("missing")
        files[to] = b
    }
}

/** In-memory [ThemeIndexStore]; [corrupt] simulates a `CorruptionException` (read returns null). */
class InMemoryThemeIndexStore : ThemeIndexStore {
    var stored: ThemeIndex? = null
    var corrupt = false
    var killOnWrite = false

    override fun read(): ThemeIndex? = if (corrupt) null else stored

    override fun write(index: ThemeIndex) {
        if (killOnWrite) {
            killOnWrite = false
            throw SimulatedKill()
        }
        corrupt = false
        stored = index
    }
}

class MapBuiltInThemeSource(private val files: Map<String, ByteArray>) : BuiltInThemeSource {
    override fun names(): List<String> = files.keys.sorted()

    override fun read(name: String): ByteArray? = files[name]
}

/**
 * [ThemeRepository] fake: [state] is set directly by the test; calls are recorded. [apply] switches to a theme
 * registered in [themes] (resolved pairs), bumping the generation like the real repository.
 */
class FakeThemeRepository(
    initial: ThemeState = ThemeState(loaded = true),
) : ThemeRepository {
    override val state = MutableStateFlow(initial)
    val themes = linkedMapOf<String, ThemePair>()
    var starts = 0
        private set
    val applied = mutableListOf<String>()
    val firstFrames = mutableListOf<Int>()
    var bootSucceeded = 0
        private set
    var stableRenders = 0
        private set

    override fun start() {
        starts++
    }

    override fun apply(id: String) {
        applied += id
        val pair = themes[id] ?: return
        state.update {
            it.copy(
                themes = pair,
                activeId = id,
                banner = null,
                applyGeneration = it.applyGeneration + 1,
                awaitingFirstFrame = true,
            )
        }
    }

    override fun onFirstFrame(generation: Int) {
        firstFrames += generation
        state.update { if (it.applyGeneration == generation) it.copy(awaitingFirstFrame = false) else it }
    }

    override fun onBootSucceeded() {
        bootSucceeded++
    }

    override fun onStableRender() {
        stableRenders++
    }

    override fun dismissBanner() {
        state.update { it.copy(banner = null) }
    }

    override suspend fun library(): List<ThemeSummary> =
        listOf(ThemeSummary(StrataDefault.ID, StrataDefault.NAME, builtIn = true, applicable = true)) +
            themes.keys.map { ThemeSummary(it, it, builtIn = false, applicable = true) }
}
