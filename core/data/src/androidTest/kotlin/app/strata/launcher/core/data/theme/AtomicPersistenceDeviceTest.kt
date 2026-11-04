package app.strata.launcher.core.data.theme

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
import app.strata.launcher.core.theme.store.ThemeLibrary
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * T-THEME-45 (level I): a writer process saves themes through the real AtomicFile store and DataStore index; the
 * test kills it with `Process.killProcess` at random moments until 1 000 saves happened. After every kill each
 * theme file is a complete old or new version (never torn, never older than before), and a fresh library
 * reconciles the index with the directory.
 */
@RunWith(AndroidJUnit4::class)
class AtomicPersistenceDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val random = Random(45)

    @Before
    fun clean() {
        ThemeWriterService.root(context).deleteRecursively()
    }

    @Test
    fun tTheme45_thousandSavesWithRandomKills() {
        val lastSeen = HashMap<String, Long>()
        var saves = 0L
        var kills = 0
        while (saves < TARGET_SAVES && kills < MAX_KILLS) {
            runWriterAndKill(delayMs = random.nextLong(MIN_RUN_MS, MAX_RUN_MS))
            kills++
            saves = verify(lastSeen)
        }
        assertWithMessage("saves after $kills kills").that(saves).isAtLeast(TARGET_SAVES)
    }

    private fun runWriterAndKill(delayMs: Long) {
        val connected = CountDownLatch(1)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) = connected.countDown()

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
        val intent = Intent(context, ThemeWriterService::class.java)
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

    private fun writerPid(): Int? {
        val am = context.getSystemService(ActivityManager::class.java)
        return am.runningAppProcesses
            ?.firstOrNull { it.processName == context.packageName + WRITER_SUFFIX }
            ?.pid
    }

    /** Checks every file and the index; returns the number of completed saves (highest generation + 1). */
    private fun verify(lastSeen: HashMap<String, Long>): Long {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val (files, library) = ThemeWriterService.library(context, scope)
            var maxGen = -1L
            for (name in files.list(ThemeLibrary.USER_DIR).filter { it.endsWith(ThemeLibrary.SUFFIX) }) {
                val bytes = files.read("${ThemeLibrary.USER_DIR}/$name")
                val gen = bytes?.let(ThemeWriterService::generation)
                assertWithMessage("$name is a complete theme").that(gen).isNotNull()
                val previous = lastSeen[name] ?: -1L
                assertWithMessage("$name went back from G$previous").that(gen!!).isAtLeast(previous)
                lastSeen[name] = gen
                maxGen = maxOf(maxGen, gen)
            }
            library.boot()
            val indexed =
                library.currentIndex.entries
                    .map { it.id.lowercase() + ThemeLibrary.SUFFIX }
                    .toSet()
            assertWithMessage("index reconciled with the directory").that(indexed).isEqualTo(lastSeen.keys)
            return maxGen + 1
        } finally {
            runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        }
    }

    private companion object {
        const val TARGET_SAVES = 1_000L
        const val MAX_KILLS = 400
        const val MIN_RUN_MS = 20L
        const val MAX_RUN_MS = 250L
        const val BIND_TIMEOUT_S = 10L
        const val KILL_TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
        const val WRITER_SUFFIX = ":t45writer"
    }
}
