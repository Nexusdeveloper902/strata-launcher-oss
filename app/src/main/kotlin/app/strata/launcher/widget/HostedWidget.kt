package app.strata.launcher.widget

import android.appwidget.AppWidgetHostView
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import app.strata.launcher.core.platformapi.widgets.WidgetHostController
import app.strata.launcher.feature.widgets.WidgetErrorTile
import app.strata.launcher.platform.widgets.StrataWidgetHostView

/**
 * The hosted widget [id] (ANDROID_PLATFORM §7 "Compose interop"): the host view kept by [host] for that ID, wrapped
 * in a non-lazy `AndroidView`. The same view is re-attached after a configuration change, never re-created, and it is
 * taken out of a previous parent first, so one ID never has two views. The area's size is passed to the provider. A
 * view that cannot be created shows the error tile. Only `:app` unwraps the host view (ARCHITECTURE §3).
 */
@Composable
fun HostedWidget(
    host: WidgetHostController,
    id: Int,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    key(id) {
        val view = remember(id) { host.hostView(id)?.native as? AppWidgetHostView }
        if (view == null) {
            WidgetErrorTile(modifier)
        } else {
            HostView(host, id, view, onLongPress, modifier)
        }
    }
}

@Composable
private fun HostView(
    host: WidgetHostController,
    id: Int,
    view: AppWidgetHostView,
    onLongPress: () -> Unit,
    modifier: Modifier,
) {
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density
    AndroidView(
        factory = {
            (view.parent as? ViewGroup)?.removeView(view)
            (view as? StrataWidgetHostView)?.longPressListener = { currentOnLongPress() }
            view
        },
        modifier = modifier.onSizeChanged { size = it },
        onRelease = { released: View -> (released as? StrataWidgetHostView)?.longPressListener = null },
    )
    LaunchedEffect(id, size) {
        // The provider learns the area's size; the binder calls run on the host's IO dispatcher.
        if (size != IntSize.Zero) host.updateSize(id, size.width / density, size.height / density)
    }
}
