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
 * (`-Pandroid.testInstrumentationRunnerArguments.cycles=N` for a shorter run).
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
        val intent = Intent(context, StoreWriterService::class.java).putExtra(StoreWriterService.EXTRA_KIND, kind.name)
        check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) { "cannot bind the writer" }
        check(connected.await(BIND_TIMEOUT_S, TimeUnit.SECONDS)) { "writer did not start" }
        SystemClock.sleep(delayMs)
        val pid = writerPid() ?: error("writer process not found")
        // Unbind first so the system does not restart the bound service, then kill mid-write.
        context.unbindService(connection)
        Process.killProcess(pid)
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
                }
            } else {
                val label = RoomOverridesRepository(lazy).overrides.first()[StoreWriterService.RENAMED]?.customLabel
                if (label != null) {
                    val g = label.removePrefix("G").toLongOrNull()
                    assertWithMessage("cycle $cycle: label $label").that(g).isNotNull()
                    assertWithMessage("cycle $cycle: label went back").that(g!!).isAtLeast(lastRename)
                    lastRename = g
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
        const val WRITER_SUFFIX = ":rel03writer"
    }
}
