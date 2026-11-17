package app.strata.launcher.platform.notifications

import android.content.ComponentName
import android.os.Process
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.common.log.LogLevel
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.BadgeKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.CoroutineContext

/**
 * T-PERF-14 (NFR-PERF-05, battery): a burst of notification updates costs one publication of the counts, not one per
 * update, and nothing is logged (so nothing reaches the file log). The listener's callbacks arrive on main and only
 * enqueue; the worker applies everything queued before it publishes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class BadgeBurstTest {
    private class Ops : ListenerOps {
        override val unsupported = false

        override fun granted() = true

        override fun serialOf(user: UserHandle) = 0L

        override fun activeNotifications(service: NotificationListenerService) = emptyList<RawNotification>()

        override fun unbind(service: NotificationListenerService) = Unit

        override fun rebind() = Unit
    }

    private class CountingLogger : Logger {
        var entries = 0

        override fun log(
            level: LogLevel,
            tag: String,
            message: String,
            throwable: Throwable?,
        ) {
            entries++
        }
    }

    /** The main dispatcher stand-in: counts the hops onto it. */
    private class Counting(
        private val inner: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        var dispatches = 0

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            dispatches++
            inner.dispatch(context, block)
        }
    }

    /** The worker and the main thread run on separate clocks, so a publication can be held "in flight". */
    private val scheduler = TestCoroutineScheduler()
    private val mainScheduler = TestCoroutineScheduler()
    private val worker = StandardTestDispatcher(scheduler)
    private val main = Counting(StandardTestDispatcher(mainScheduler))
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val logger = CountingLogger()
    private val source =
        AndroidBadgeSource(
            ApplicationProvider.getApplicationContext(),
            ComponentName("app.strata.launcher", "app.strata.launcher.badges.BadgeListenerService"),
            worker = worker,
            publish = main,
            scope = scope,
            logger = logger,
            ops = Ops(),
        )
    private val service = object : NotificationListenerService() {}

    @After
    fun tearDown() {
        scope.cancel()
        // The process-wide hook the listener reports to: no source of this test may outlive it.
        if (AndroidBadgeSource.instance === source) AndroidBadgeSource.instance = null
    }

    /** Runs the worker and the main thread in turn until the hand-offs between them are done. */
    private fun drain() {
        repeat(MAX_ROUNDS) {
            scheduler.advanceUntilIdle()
            mainScheduler.advanceUntilIdle()
        }
    }

    private fun raw(id: String) = RawNotification(id, "fixture.chat", Process.myUserHandle(), false, false, 3, true)

    private fun connected() {
        source.setActive(true)
        source.onConnected(service)
        drain()
        main.dispatches = 0
        logger.entries = 0
    }

    /** T-PERF-14: 100 updates of one conversation (e.g. a messenger re-posting it) publish at most twice. */
    @Test
    fun tPerf14_hundredUpdatesOfOneNotificationPublishOnce() {
        connected()
        repeat(100) { source.onPosted(raw("conversation")) }
        drain()
        assertThat(source.badges.value).containsExactly(BadgeKey("fixture.chat", 0L), 1)
        assertThat(main.dispatches).isAtMost(MAX_PUBLISHES)
        assertThat(logger.entries).isEqualTo(0)
    }

    /** T-PERF-14: 100 new notifications and their ranking updates, interleaved, publish at most twice too. */
    @Test
    fun tPerf14_hundredNewNotificationsPublishOnce() {
        connected()
        repeat(100) { i ->
            source.onPosted(raw("n$i"))
            source.onRanking(mapOf("n$i" to (3 to true)))
        }
        drain()
        assertThat(source.badges.value).containsExactly(BadgeKey("fixture.chat", 0L), 100)
        assertThat(main.dispatches).isAtMost(MAX_PUBLISHES)
        assertThat(logger.entries).isEqualTo(0)
    }

    /** T-PERF-14: an update that changes no count (same notification again, after the burst) costs no hop at all. */
    @Test
    fun tPerf14_unchangedCountsAreNotRepublished() {
        connected()
        source.onPosted(raw("a"))
        drain()
        main.dispatches = 0
        repeat(10) {
            source.onPosted(raw("a"))
            drain()
        }
        assertThat(main.dispatches).isEqualTo(0)
    }

    /** T-PERF-14: a burst that removes every notification publishes the empty map once (badges back to zero). */
    @Test
    fun tPerf14_removalBackToZero() {
        connected()
        repeat(3) { source.onPosted(raw("n$it")) }
        drain()
        assertThat(source.badges.value).containsExactly(BadgeKey("fixture.chat", 0L), 3)
        main.dispatches = 0
        repeat(3) { source.onRemoved("n$it") }
        drain()
        assertThat(source.badges.value).isEmpty()
        assertThat(main.dispatches).isEqualTo(1)
    }

    /**
     * T-PERF-14: events that arrive while a publication is still on its way to main are not lost: once it lands, the
     * worker applies them and publishes the newer counts.
     */
    @Test
    fun tPerf14_eventDuringPublishIsApplied() {
        connected()
        source.onPosted(raw("a"))
        scheduler.advanceUntilIdle() // the worker is now suspended in the hop to main, which has not run
        assertThat(source.badges.value).isEmpty()
        source.onPosted(raw("b"))
        source.onPosted(raw("c"))
        mainScheduler.advanceUntilIdle() // the first publication lands: one alert
        assertThat(source.badges.value).containsExactly(BadgeKey("fixture.chat", 0L), 1)
        drain()
        assertThat(source.badges.value).containsExactly(BadgeKey("fixture.chat", 0L), 3)
        assertThat(main.dispatches).isEqualTo(2)
    }

    private companion object {
        const val MAX_PUBLISHES = 2
        const val MAX_ROUNDS = 10
    }
}
