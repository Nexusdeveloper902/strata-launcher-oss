package app.strata.launcher.core.data.rel

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.strata.launcher.core.data.LazyResource
import app.strata.launcher.core.data.overrides.RoomFavoritesRepository
import app.strata.launcher.core.data.overrides.RoomOverridesRepository
import app.strata.launcher.core.data.rel.StoreWriterService.Kind
import app.strata.launcher.core.data.theme.ThemeWriterService
import app.strata.launcher.core.platformapi.settings.ResetCause
import app.strata.launcher.core.platformapi.settings.ResetNotices
import app.strata.launcher.core.theme.store.ThemeLibrary
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * T-REL-03 (level I), NFR-REL-02: 1 000 cycles of "write, get killed at a random moment" over the four user-data
 * paths — favourites reorder (one Room transaction), rename (Room), settings (Proto DataStore, three fields in one
 * update) and theme save (AtomicFile + DataStore index) — in turn, through the production store classes in their own
 * process. After every kill each store is a complete old or new state, never mixed, and SQLite's
 * `PRAGMA integrity_check` reports `ok`. Runs on a device: `./gradlew :core:data:connectedDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=app.strata.launcher.core.data.rel.KillDuringWriteDeviceTest`
 * (`-Pandroid.testInstrumentationRunnerArguments.cycles=N` for a shorter run). Each kill comes a random 5–150 ms after
 * the writer's first committed write, and every store must show committed progress (generation ≥ 1) in at least half
 * of its cycles, so the campaign cannot pass without writes being interrupted.
 */
@RunWith(AndroidJUnit4::class)
class KillDuringWriteDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val random = Random(3)
    private val cycles =
        InstrumentationRegistry.getArguments().getString("cycles")?.toIntOrNull() ?: DEFAULT_CYCLES
    private var lastRename = -1L
    private var lastTheme = -1L

    /** Per store, the cycles whose check saw a committed write of generation ≥ 1 (the campaign is not vacuous). */
    private val progressed = HashMap<Kind, Int>()

    @Before
    fun clean() {
        StoreWriterService.root(context).deleteRecursively()
        ThemeWriterService.root(context).deleteRecursively()
    }

    @After
    fun cleanUp() = clean()

    @Test
    fun tRel03_killDuringWrite() {
        val kinds = Kind.values()
        repeat(cycles) { i ->
            val kind = kinds[i % kinds.size]
            runWriterAndKill(kind, delayMs = random.nextLong(MIN_RUN_MS, MAX_RUN_MS))
            verify(kind, i)
        }
        // Kills come after the first commit, so nearly every cycle has progressed; a rotation back to the add order
        // (reorder, 1 in 10) is the only state that does not prove it. Half per store is a safe floor.
        val perKind = cycles / kinds.size
        for (kind in kinds) {
            assertWithMessage("$kind: cycles with a committed write of generation ≥ 1")
                .that(progressed[kind] ?: 0)
                .isAtLeast(maxOf(1, perKind / 2))
        }
    }

    private fun progressedOnce(kind: Kind) {
        progressed[kind] = (progressed[kind] ?: 0) + 1
    }

    private fun runWriterAndKill(
        kind: Kind,
        delayMs: Long,
    ) {
        val connected = CountDownLatch(1)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) = connected.countDown()

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
        val ready = StoreWriterService.ready(context, kind)
        ready.delete()
        val intent = Intent(context, StoreWriterService::class.java).putExtra(StoreWriterService.EXTRA_KIND, kind.name)
        check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) { "cannot bind the writer" }
        var bound = true
        try {
            check(connected.await(BIND_TIMEOUT_S, TimeUnit.SECONDS)) { "writer did not start" }
            // The random delay counts from the writer's first committed write, so every kill lands among writes.
            val start = SystemClock.uptimeMillis()
            while (!ready.exists()) {
                check(SystemClock.uptimeMillis() - start < READY_TIMEOUT_MS) { "writer committed nothing ($kind)" }
                SystemClock.sleep(READY_POLL_MS)
            }
            SystemClock.sleep(delayMs)
            val pid = writerPid() ?: error("writer process not found")
            // Unbind first so the system does not restart the bound service, then kill mid-write.
            context.unbindService(connection)
            bound = false
            Process.killProcess(pid)
        } finally {
            if (bound) {
                runCatching { context.unbindService(connection) }
                writerPid()?.let(Process::killProcess)
            }
        }
        val deadline = SystemClock.uptimeMillis() + KILL_TIMEOUT_MS
        while (writerPid() != null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(POLL_MS)
        check(writerPid() == null) { "writer survived the kill" }
    }

    private fun writerPid(): Int? =
        context
            .getSystemService(ActivityManager::class.java)
            .runningAppProcesses
            ?.firstOrNull { it.processName == context.packageName + WRITER_SUFFIX }
            ?.pid

    private fun verify(
        kind: Kind,
        cycle: Int,
    ) = runBlocking {
        when (kind) {
            Kind.REORDER, Kind.RENAME -> verifyRoom(kind, cycle)
            Kind.SETTINGS -> verifySettings(cycle)
            Kind.THEME -> verifyTheme(cycle)
        }
    }

    private suspend fun verifyRoom(
        kind: Kind,
        cycle: Int,
    ) {
        val db = StoreWriterService.database(context)
        try {
            val integrity =
                db.openHelper.writableDatabase.query("PRAGMA integrity_check").use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(0)) }
                }
            assertWithMessage("cycle $cycle ($kind): integrity_check").that(integrity).containsExactly("ok")
            val lazy = LazyResource(Dispatchers.IO) { db }
            if (kind == Kind.REORDER) {
                val order = RoomFavoritesRepository(lazy).favorites.first()
                if (order.isNotEmpty()) {
                    // Either before the first add finished (a prefix of the keys) or one complete rotation.
                    val complete =
                        (0 until StoreWriterService.FAVORITES).any {
                            order ==
                                StoreWriterService.order(it.toLong())
                        }
                    val adding = order == StoreWriterService.KEYS.take(order.size)
                    assertWithMessage("cycle $cycle: favourites $order").that(complete || adding).isTrue()
                    if (complete && order != StoreWriterService.KEYS) progressedOnce(kind)
                }
            } else {
                val label = RoomOverridesRepository(lazy).overrides.first()[StoreWriterService.RENAMED]?.customLabel
                if (label != null) {
                    val g = label.removePrefix("G").toLongOrNull()
                    assertWithMessage("cycle $cycle: label $label").that(g).isNotNull()
                    assertWithMessage("cycle $cycle: label went back").that(g!!).isAtLeast(lastRename)
                    lastRename = g
                    if (g >= 1) progressedOnce(kind)
                }
            }
        } finally {
            db.close()
        }
    }

    private suspend fun verifySettings(cycle: Int) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val notices = CountingNotices()
        try {
            val s = StoreWriterService.settings(context, scope, notices).settings.first()
            assertWithMessage("cycle $cycle: settings reset as corrupt").that(notices.reported).isEmpty()
            assertWithMessage("cycle $cycle: mixed settings $s")
                .that(setOf(s.hapticsEnabled, s.listTwoColumn, s.hideStatusBar))
                .hasSize(1)
            progressedOnce(Kind.SETTINGS)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    private suspend fun verifyTheme(cycle: Int) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val (files, library) = ThemeWriterService.library(context, scope)
            for (name in files.list(ThemeLibrary.USER_DIR).filter { it.endsWith(ThemeLibrary.SUFFIX) }) {
                val g = files.read("${ThemeLibrary.USER_DIR}/$name")?.let(ThemeWriterService::generation)
                assertWithMessage("cycle $cycle: $name is a complete theme").that(g).isNotNull()
                assertWithMessage("cycle $cycle: $name went back").that(g!!).isAtLeast(lastTheme)
                lastTheme = g
                if (g >= 1) progressedOnce(Kind.THEME)
            }
            // The index may lag the files; a fresh library reconciles it without losing the theme (THEME_SYSTEM §11.2).
            library.boot()
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    private class CountingNotices : ResetNotices {
        val reported = mutableListOf<ResetCause>()
        override val causes: StateFlow<Set<ResetCause>> = MutableStateFlow(emptySet())

        override fun report(cause: ResetCause) {
            reported += cause
        }

        override fun dismiss() = Unit
    }

    private companion object {
        const val DEFAULT_CYCLES = 1_000
        const val MIN_RUN_MS = 5L
        const val MAX_RUN_MS = 150L
        const val BIND_TIMEOUT_S = 10L
        const val KILL_TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
        const val READY_TIMEOUT_MS = 10_000L
        const val READY_POLL_MS = 2L
        const val WRITER_SUFFIX = ":rel03writer"
    }
}
