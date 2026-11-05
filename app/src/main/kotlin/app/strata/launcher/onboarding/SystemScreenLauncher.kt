package app.strata.launcher.onboarding

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.platformapi.system.SystemIntent
import app.strata.launcher.feature.onboarding.SetDefaultFlow
import app.strata.launcher.platform.system.SystemIntentFactory
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Starts the system screens of the set-default flow (role request, home settings chain) for a result
 * (FR-ONBOARD-02/03, ANDROID_PLATFORM §2). Construct it as an activity field: the result launcher must be registered
 * before the activity is started. It keeps no state about the screen in flight: whoever starts one records that in
 * its view model, and every result is offered to all receivers registered by the current activity instance, which
 * ignore it unless they started a screen. So a result delivered to a recreated activity (rotation, dark mode while
 * the role dialog is up) still reaches the view model that is waiting for it.
 */
class SystemScreenLauncher(
    private val activity: ComponentActivity,
    private val logger: () -> Logger,
) {
    private val receivers = CopyOnWriteArrayList<(ok: Boolean, now: Long) -> Unit>()

    private val launcher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val now = SystemClock.elapsedRealtime()
            val ok = result.resultCode == Activity.RESULT_OK
            receivers.forEach { it(ok, now) }
        }

    /** Registers a result receiver for this activity instance; call from `onCreate`. */
    fun onResult(receiver: (ok: Boolean, now: Long) -> Unit) {
        receivers += receiver
    }

    /** Starts [flow]'s launch requests and routes the results back to it. Call once per flow, from `onCreate`. */
    fun bind(flow: SetDefaultFlow) {
        onResult(flow::onResult)
        activity.lifecycleScope.launch {
            flow.requests.collect { request ->
                flow.onLaunching(request.attempt, SystemClock.elapsedRealtime())
                if (!start(request.intents)) flow.onNoActivity(request.attempt)
            }
        }
    }

    /** Starts the first of [intents] that resolves, for a result. False when none of them exists. */
    fun start(intents: List<SystemIntent>): Boolean {
        for (target in intents) {
            val intent = SystemIntentFactory.create(activity, target) ?: continue
            try {
                launcher.launch(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                logger().w(TAG, "no activity for $target", e)
            } catch (e: SecurityException) {
                logger().w(TAG, "not allowed to start $target", e)
            }
        }
        return false
    }

    private companion object {
        const val TAG = "SystemScreen"
    }
}
