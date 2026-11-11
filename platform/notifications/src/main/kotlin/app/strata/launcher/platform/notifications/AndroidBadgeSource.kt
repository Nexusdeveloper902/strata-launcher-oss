package app.strata.launcher.platform.notifications

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.BadgeKey
import app.strata.launcher.core.platformapi.apps.LaunchError
import app.strata.launcher.core.platformapi.apps.LaunchResult
import app.strata.launcher.core.platformapi.notifications.BadgeSource
import app.strata.launcher.core.platformapi.notifications.ListenerAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [BadgeSource] over [BadgeListener] (ANDROID_PLATFORM §8, ARCHITECTURE §6).
 *
 * The listener's main-thread callbacks only enqueue an event. One consumer on [worker] (a single-thread dispatcher)
 * owns the active-notification facts, resolves profiles to serial numbers, makes the binder calls
 * (`getActiveNotifications`, `getCurrentRanking`, `requestUnbind`/`requestRebind`) and publishes the counts and the
 * access state on [publish] (the main dispatcher). Nothing is ever written to disk or logged beyond event kinds.
 */
class AndroidBadgeSource internal constructor(
    context: Context,
    /** Our listener component (`.badges.BadgeListenerService`, declared by `:app`). */
    private val listener: ComponentName,
    private val worker: CoroutineDispatcher,
    private val publish: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val logger: Logger,
    /** The platform calls; replaced in the state-machine tests. */
    ops: ListenerOps?,
) : BadgeSource {
    constructor(
        context: Context,
        listener: ComponentName,
        worker: CoroutineDispatcher,
        publish: CoroutineDispatcher,
        scope: CoroutineScope,
        logger: Logger,
    ) : this(context, listener, worker, publish, scope, logger, null)

    private val appContext = context.applicationContext
    private val ops: ListenerOps = ops ?: PlatformListenerOps(appContext, listener)
    private val accessState = MutableStateFlow(ListenerAccess.NOT_GRANTED)
    private val badgeState = MutableStateFlow<Map<BadgeKey, Int>>(emptyMap())
    private val events = Channel<Event>(Channel.UNLIMITED)

    // Worker-only state.
    private val facts = HashMap<String, NotificationFacts>()
    private val serials = HashMap<UserHandle, Long>()

    /** Settings field 13; null until the first value arrives (a listener bound at boot waits for it). */
    private var active: Boolean? = null

    @Volatile private var connected: NotificationListenerService? = null

    override val access: StateFlow<ListenerAccess> = accessState
    override val badges: StateFlow<Map<BadgeKey, Int>> = badgeState

    init {
        instance = this
        scope.launch(worker) {
            for (event in events) {
                try {
                    handle(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    // A revoked grant races every call (SecurityException); never crash home for a badge.
                    logger.w(TAG, "badge event ${event::class.simpleName} failed: ${e.javaClass.simpleName}")
                    if (event is Event.Refresh) event.reply.complete(accessState.value)
                }
            }
        }
    }

    override fun setActive(active: Boolean) {
        events.trySend(Event.Active(active))
    }

    /**
     * Runs in the event queue, in order with the listener's connect/disconnect, so a stale read can never overwrite a
     * newer state (review of PR #24).
     */
    override suspend fun refresh(): ListenerAccess {
        val reply = CompletableDeferred<ListenerAccess>()
        events.trySend(Event.Refresh(reply))
        return reply.await()
    }

    override fun openGrantScreen(): LaunchResult {
        val detail =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString())
            } else {
                null
            }
        val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        // "A matching Activity may not exist" (ANDROID_PLATFORM §8): try the detail page, then the list.
        for (intent in listOfNotNull(detail, list)) {
            try {
                appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return Outcome.Ok(Unit)
            } catch (e: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                continue
            }
        }
        return Outcome.Err(LaunchError.NOT_FOUND)
    }

    internal fun onConnected(service: NotificationListenerService) {
        events.trySend(Event.Connected(service))
    }

    internal fun onDisconnected(service: NotificationListenerService) {
        events.trySend(Event.Disconnected(service))
    }

    internal fun onPosted(raw: RawNotification) {
        events.trySend(Event.Posted(raw))
    }

    internal fun onRemoved(key: String) {
        events.trySend(Event.Removed(key))
    }

    internal fun onRanking(rankings: Map<String, Pair<Int, Boolean>>) {
        events.trySend(Event.Ranking(rankings))
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Connected -> {
                connected = event.service
                setAccess(ListenerAccess.CONNECTED)
                when (active) {
                    true -> load(event.service)
                    false -> unbind(event.service)
                    null -> Unit // wait for the setting
                }
            }

            is Event.Disconnected -> {
                if (connected !== event.service) return
                connected = null
                facts.clear()
                publishCounts()
                setAccess(readAccess())
            }

            is Event.Posted -> {
                if (active != true || connected == null) return
                facts[event.raw.key] = factsOf(event.raw)
                publishCounts()
            }

            is Event.Removed -> {
                if (facts.remove(event.key) != null) publishCounts()
            }

            is Event.Ranking -> {
                var changed = false
                for ((key, f) in facts) {
                    val (importance, badge) = event.rankings[key] ?: continue
                    if (importance != f.importance || badge != f.canShowBadge) {
                        facts[key] = f.copy(importance = importance, canShowBadge = badge)
                        changed = true
                    }
                }
                if (changed) publishCounts()
            }

            is Event.Refresh -> {
                val a = readAccess()
                if (a == ListenerAccess.GRANTED && active == true) rebind()
                setAccess(a)
                event.reply.complete(a)
            }

            is Event.Active -> {
                val was = active
                active = event.active
                if (was == event.active) return
                val service = connected
                if (!event.active) {
                    facts.clear()
                    publishCounts()
                    if (service != null) unbind(service)
                } else if (service != null) {
                    load(service)
                } else if (readAccess() == ListenerAccess.GRANTED) {
                    rebind()
                }
            }
        }
    }

    /** `onListenerConnected` is the point where `getActiveNotifications()` is safe (ANDROID_PLATFORM §8). */
    private suspend fun load(service: NotificationListenerService) {
        val active = ops.activeNotifications(service)
        facts.clear()
        for (raw in active) facts[raw.key] = factsOf(raw)
        publishCounts()
    }

    private fun factsOf(raw: RawNotification): NotificationFacts =
        NotificationFacts(
            key = raw.key,
            badgeKey = BadgeKey(raw.packageName, serialOf(raw.user)),
            ongoing = raw.ongoing,
            groupSummary = raw.groupSummary,
            importance = raw.importance,
            canShowBadge = raw.canShowBadge,
        )

    private fun serialOf(user: UserHandle): Long = serials.getOrPut(user) { ops.serialOf(user) }

    /** Badges off: the system unbinds us; the access is what the settings say, no longer CONNECTED. */
    private suspend fun unbind(service: NotificationListenerService) {
        if (connected === service) connected = null
        ops.unbind(service)
        logger.d(TAG, "listener unbind requested (badges off)")
        setAccess(readAccess())
    }

    /** U-020: OEM task killers may drop the listener; a granted, wanted listener asks to be bound again. */
    private fun rebind() {
        if (connected != null) return
        try {
            ops.rebind()
        } catch (e: RuntimeException) {
            logger.w(TAG, "listener rebind failed: ${e.javaClass.simpleName}")
        }
    }

    private fun readAccess(): ListenerAccess {
        if (ops.unsupported) return ListenerAccess.UNSUPPORTED
        if (connected != null) return ListenerAccess.CONNECTED
        val granted =
            try {
                ops.granted()
            } catch (e: RuntimeException) {
                false
            }
        return if (granted) ListenerAccess.GRANTED else ListenerAccess.NOT_GRANTED
    }

    private suspend fun setAccess(access: ListenerAccess) {
        withContext(publish) { accessState.value = access }
    }

    private suspend fun publishCounts() {
        val counts = BadgeCounter.counts(facts.values)
        withContext(publish) { badgeState.value = counts }
    }

    private sealed interface Event {
        class Connected(val service: NotificationListenerService) : Event

        class Disconnected(val service: NotificationListenerService) : Event

        class Posted(val raw: RawNotification) : Event

        class Removed(val key: String) : Event

        class Ranking(val rankings: Map<String, Pair<Int, Boolean>>) : Event

        class Active(val active: Boolean) : Event

        class Refresh(val reply: CompletableDeferred<ListenerAccess>) : Event
    }

    companion object {
        private const val TAG = "Badges"

        /** The process's source; the listener (created by the system) reports to it. */
        @Volatile
        var instance: AndroidBadgeSource? = null
            private set
    }
}

/** The platform calls of [AndroidBadgeSource]: one seam, so the event state machine runs in JVM tests. */
internal interface ListenerOps {
    /** FR-NOTIF-03: low-RAM device on API ≤ 29. */
    val unsupported: Boolean

    /** Listener access in the system settings. */
    fun granted(): Boolean

    fun serialOf(user: UserHandle): Long

    /** `getActiveNotifications()` with `getCurrentRanking()`; only flags and rankings are kept. */
    fun activeNotifications(service: NotificationListenerService): List<RawNotification>

    fun unbind(service: NotificationListenerService)

    fun rebind()
}

private class PlatformListenerOps(
    private val context: Context,
    private val listener: ComponentName,
) : ListenerOps {
    override val unsupported: Boolean by lazy {
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
            context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    }

    override fun granted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(listener) ==
                true
        } else {
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }

    override fun serialOf(user: UserHandle): Long =
        context.getSystemService(UserManager::class.java)?.getSerialNumberForUser(user) ?: -1L

    override fun activeNotifications(service: NotificationListenerService): List<RawNotification> {
        val active = service.activeNotifications.orEmpty()
        val ranking = service.currentRanking
        return active.map { BadgeListener.rawOf(it, ranking) }
    }

    override fun unbind(service: NotificationListenerService) = service.requestUnbind()

    override fun rebind() = NotificationListenerService.requestRebind(listener)
}
