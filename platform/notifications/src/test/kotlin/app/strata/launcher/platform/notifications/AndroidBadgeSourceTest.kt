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
import app.strata.launcher.core.platformapi.notifications.ListenerAccess
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The event state machine of [AndroidBadgeSource] (level R, platform calls faked through [ListenerOps]). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidBadgeSourceTest {
    private class FakeOps : ListenerOps {
        override var unsupported = false
        var granted = true
        var active: List<RawNotification> = emptyList()
        val unbound = mutableListOf<NotificationListenerService>()
        var rebinds = 0

        override fun granted() = granted

        override fun serialOf(user: UserHandle) = 0L

        override fun activeNotifications(service: NotificationListenerService) = active

        override fun unbind(service: NotificationListenerService) {
            unbound += service
        }

        override fun rebind() {
            rebinds++
        }
    }

    private object Silent : Logger {
        override fun log(
            level: LogLevel,
            tag: String,
            message: String,
            throwable: Throwable?,
        ) = Unit
    }

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val ops = FakeOps()
    private val source =
        AndroidBadgeSource(
            ApplicationProvider.getApplicationContext(),
            ComponentName("app.strata.launcher", "app.strata.launcher.badges.BadgeListenerService"),
            worker = dispatcher,
            publish = dispatcher,
            scope = scope,
            logger = Silent,
            ops = ops,
        )
    private val service = object : NotificationListenerService() {}
    private val key = BadgeKey("fixture.x", 0L)

    @After
    fun tearDown() = scope.cancel()

    private fun raw(
        id: String,
        ongoing: Boolean = false,
    ) = RawNotification(id, "fixture.x", Process.myUserHandle(), ongoing, false, 3, true)

    @Test
    fun connectBeforeTheSettingWaits_thenLoadsWhenOn() {
        ops.active = listOf(raw("a"), raw("b"), raw("c", ongoing = true))
        source.onConnected(service)
        assertThat(source.access.value).isEqualTo(ListenerAccess.CONNECTED)
        assertThat(source.badges.value).isEmpty() // setting not known yet: nothing read
        source.setActive(true)
        assertThat(source.badges.value).containsExactly(key, 2)
    }

    @Test
    fun postedAndRemovedWhileActive() {
        source.setActive(true)
        source.onConnected(service)
        source.onPosted(raw("a"))
        source.onPosted(raw("b"))
        assertThat(source.badges.value).containsExactly(key, 2)
        source.onRemoved("a")
        assertThat(source.badges.value).containsExactly(key, 1)
        source.onRanking(mapOf("b" to (2 to true))) // the user made the channel silent
        assertThat(source.badges.value).isEmpty()
    }

    /** Review (a): turning badges off unbinds and publishes the settings' access; it never stays CONNECTED. */
    @Test
    fun offUnbindsAndLeavesConnected() {
        source.setActive(true)
        source.onConnected(service)
        source.onPosted(raw("a"))
        source.setActive(false)
        assertThat(ops.unbound).containsExactly(service)
        assertThat(source.badges.value).isEmpty()
        assertThat(source.access.value).isEqualTo(ListenerAccess.GRANTED)
        source.onDisconnected(service) // the system's unbind follows; ignored, state already right
        assertThat(source.access.value).isEqualTo(ListenerAccess.GRANTED)
        source.onPosted(raw("b")) // nothing is received while off
        assertThat(source.badges.value).isEmpty()
    }

    /** Review (b): refresh runs in the queue, so it reports CONNECTED after a connect, not a stale GRANTED. */
    @Test
    fun refreshIsOrderedWithConnect() {
        source.setActive(true)
        assertThat(runBlocking { source.refresh() }).isEqualTo(ListenerAccess.GRANTED)
        assertThat(ops.rebinds).isEqualTo(2) // on Active(true) and on refresh: granted, wanted, not connected
        source.onConnected(service)
        assertThat(runBlocking { source.refresh() }).isEqualTo(ListenerAccess.CONNECTED)
        assertThat(source.access.value).isEqualTo(ListenerAccess.CONNECTED)
    }

    @Test
    fun revokeDisconnectsAndClears() {
        source.setActive(true)
        source.onConnected(service)
        source.onPosted(raw("a"))
        ops.granted = false
        source.onDisconnected(service)
        assertThat(source.badges.value).isEmpty()
        assertThat(source.access.value).isEqualTo(ListenerAccess.NOT_GRANTED)
    }

    @Test
    fun lowRamIsUnsupported() {
        ops.unsupported = true
        assertThat(runBlocking { source.refresh() }).isEqualTo(ListenerAccess.UNSUPPORTED)
    }
}
