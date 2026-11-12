package app.strata.launcher.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.strata.launcher.appGraph
import app.strata.launcher.platform.widgets.AndroidWidgetHost
import app.strata.launcher.platform.widgets.HostRestore
import kotlinx.coroutines.launch

/**
 * `APPWIDGET_HOST_RESTORED` (FR-WIDGET-04, ANDROID_PLATFORM §7, ARCHITECTURE §13): remaps the stored placement to the
 * restored widget IDs. A protected system broadcast; the receiver still checks the action, our host ID and the extras
 * (int arrays only, length-matched, at most 64). A placement whose old ID has no new one becomes the placeholder.
 */
class WidgetHostRestoredReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED) return
        val map =
            try {
                HostRestore.parse(
                    expectedHostId = AndroidWidgetHost.HOST_ID,
                    hostId =
                        if (intent.hasExtra(AppWidgetManager.EXTRA_HOST_ID)) {
                            intent.getIntExtra(AppWidgetManager.EXTRA_HOST_ID, -1)
                        } else {
                            null
                        },
                    oldIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS),
                    newIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS),
                )
            } catch (e: RuntimeException) {
                // A malformed parcel (BadParcelableException) is not a restore we can use.
                null
            } ?: return
        val graph = context.appGraph
        // Null when called directly (instrumentation, T-WIDGET-08) rather than by the system.
        val pending: PendingResult? = goAsync()
        graph.appScope.launch {
            try {
                graph.widgets.remap(map)
                graph.logger.i(TAG, "widget IDs remapped: ${map.size}")
            } catch (e: Exception) {
                graph.logger.w(TAG, "widget remap failed", e)
            } finally {
                pending?.finish()
            }
        }
    }

    private companion object {
        const val TAG = "WidgetRestore"
    }
}
