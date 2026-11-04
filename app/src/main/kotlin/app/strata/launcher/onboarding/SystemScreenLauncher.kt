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

/**
 * Starts the system screens of the set-default flow (role request, home settings chain) for a result and reports the
 * return (FR-ONBOARD-02/03, ANDROID_PLATFORM §2). Construct it as an activity field: the result launcher must be
 * registered before the activity is started. One screen is open at a time; its return goes to whoever started it.
 * Every intent is tried in order and `ActivityNotFoundException` falls through to the next.
 */
class SystemScreenLauncher(
    private val activity: ComponentActivity,
    private val logger: () -> Logger,
) {
    private var pending: Pending? = null

    private val launcher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val p = pending ?: return@registerForActivityResult
            pending = null
            p.onReturn(result.resultCode == Activity.RESULT_OK, SystemClock.elapsedRealtime() - p.startedAt)
        }

    /** Collects [flow]'s launch requests while the activity lives. Call once per flow, from `onCreate`. */
    fun bind(flow: SetDefaultFlow) {
        activity.lifecycleScope.launch {
            flow.requests.collect { request ->
                launch(
                    intents = request.intents,
                    onReturn = { ok, millis -> flow.onReturned(request.attempt, ok, millis) },
                    onNoActivity = { flow.onNoActivity(request.attempt) },
                )
            }
        }
    }

    /**
     * Starts the first of [intents] that resolves. [onReturn] gets `RESULT_OK` and how long the screen was open;
     * [onNoActivity] runs when none of them exists. A return after process death reaches no callback (the activity
     * re-checks the role in `onResume` anyway).
     */
    fun launch(
        intents: List<SystemIntent>,
        onReturn: (ok: Boolean, openMillis: Long) -> Unit,
        onNoActivity: () -> Unit,
    ) {
        for (target in intents) {
            val intent = SystemIntentFactory.create(activity, target) ?: continue
            try {
                pending = Pending(SystemClock.elapsedRealtime(), onReturn)
                launcher.launch(intent)
                return
            } catch (e: ActivityNotFoundException) {
                logger().w(TAG, "no activity for $target", e)
            } catch (e: SecurityException) {
                logger().w(TAG, "not allowed to start $target", e)
            }
        }
        pending = null
        onNoActivity()
    }

    private class Pending(
        val startedAt: Long,
        val onReturn: (Boolean, Long) -> Unit,
    )

    private companion object {
        const val TAG = "SystemScreen"
    }
}
