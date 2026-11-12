package app.strata.launcher.platform.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.IconImage
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.WidgetProviderRef
import app.strata.launcher.core.platformapi.widgets.BindError
import app.strata.launcher.core.platformapi.widgets.BindStep
import app.strata.launcher.core.platformapi.widgets.WidgetHostController
import app.strata.launcher.core.platformapi.widgets.WidgetHostEvent
import app.strata.launcher.core.platformapi.widgets.WidgetPreview
import app.strata.launcher.core.platformapi.widgets.WidgetProviderModel
import app.strata.launcher.core.platformapi.widgets.WidgetViewHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [WidgetHostController] on the platform `AppWidgetHost` (ANDROID_PLATFORM §7, P-3). `BIND_APPWIDGET` is never
 * declared: binding is `bindAppWidgetIdIfAllowed`, then the user's consent (`ACTION_APPWIDGET_BIND`). Binder calls
 * run on [io]. Host views are created once per ID and kept, so a configuration change re-attaches them
 * (ARCHITECTURE §10). Every platform or provider exception is caught: a broken widget never takes home down.
 */
class AndroidWidgetHost(
    context: Context,
    private val io: CoroutineDispatcher,
    private val logger: Logger,
) : WidgetHostController {
    private val appContext = context.applicationContext
    private val manager: AppWidgetManager? by lazy { AppWidgetManager.getInstance(appContext) }
    private val users by lazy { appContext.getSystemService(UserManager::class.java) }
    private val density get() = appContext.resources.displayMetrics.density

    private val eventFlow =
        MutableSharedFlow<WidgetHostEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<WidgetHostEvent> = eventFlow

    /** Host views by ID; created and attached on main, removed on delete or when the platform removes the widget. */
    private val views = ConcurrentHashMap<Int, AppWidgetHostView>()

    /** Provider info by bound ID, filled off main so [hostView] rarely needs a binder call. */
    private val infos = ConcurrentHashMap<Int, AppWidgetProviderInfo>()

    private val host: AppWidgetHost by lazy {
        object : AppWidgetHost(appContext, HOST_ID) {
            override fun onCreateView(
                context: Context,
                appWidgetId: Int,
                appWidget: AppWidgetProviderInfo?,
            ): AppWidgetHostView = StrataWidgetHostView(context)

            override fun onProvidersChanged() {
                eventFlow.tryEmit(WidgetHostEvent.PROVIDERS_CHANGED)
            }

            override fun onAppWidgetRemoved(appWidgetId: Int) {
                views.remove(appWidgetId)
                infos.remove(appWidgetId)
                eventFlow.tryEmit(WidgetHostEvent.WIDGET_REMOVED)
            }
        }
    }

    override suspend fun providers(): List<WidgetProviderModel> =
        withContext(io) {
            val manager = manager ?: return@withContext emptyList()
            val pm = appContext.packageManager
            val out = mutableListOf<WidgetProviderModel>()
            for (user in profiles()) {
                val kind = kindOf(user)
                if (kind == ProfileKind.PRIVATE) continue // P-5: nothing of the private space is ever persisted
                val serial = guard("serial") { users.getSerialNumberForUser(user) } ?: continue
                val infos = guard("providers") { manager.getInstalledProvidersForProfile(user) } ?: continue
                for (info in infos) {
                    if (hiddenFromPicker(info)) continue
                    val appLabel =
                        guard("app label") {
                            pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
                        } ?: info.provider.packageName
                    out +=
                        WidgetProviderModel(
                            ref = WidgetProviderRef(info.provider.flattenToString(), serial),
                            label = guard("label") { info.loadLabel(pm) } ?: info.provider.className,
                            appLabel = appLabel,
                            profileKind = kind,
                            minWidthDp = toDp(info.minWidth),
                            minHeightDp = toDp(info.minHeight),
                            needsConfigure = needsConfigure(info),
                        )
                }
            }
            out
        }

    override suspend fun preview(
        ref: WidgetProviderRef,
        maxWidthPx: Int,
        maxHeightPx: Int,
    ): WidgetPreview =
        withContext(io) {
            val user = userFor(ref) ?: return@withContext WidgetPreview.None
            val component = ComponentName.unflattenFromString(ref.component) ?: return@withContext WidgetPreview.None
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                val generated =
                    guard("generated preview") {
                        manager?.getWidgetPreview(component, user, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
                    }
                if (generated != null) return@withContext WidgetPreview.Generated(generated)
            }
            val info = findInfo(ref, user) ?: return@withContext WidgetPreview.None
            val dpi = appContext.resources.displayMetrics.densityDpi
            val drawable =
                guard("preview image") { info.loadPreviewImage(appContext, dpi) }
                    ?: guard("icon") { info.loadIcon(appContext, dpi) }
                    ?: return@withContext WidgetPreview.None
            render(drawable, maxWidthPx, maxHeightPx)?.let { WidgetPreview.Image(it) } ?: WidgetPreview.None
        }

    override suspend fun icon(
        ref: WidgetProviderRef,
        sizePx: Int,
    ): IconImage? =
        withContext(io) {
            val user = userFor(ref) ?: return@withContext null
            val info = findInfo(ref, user) ?: return@withContext null
            val drawable =
                guard("icon") { info.loadIcon(appContext, appContext.resources.displayMetrics.densityDpi) }
                    ?: return@withContext null
            render(drawable, sizePx, sizePx)
        }

    override suspend fun allocateAndBind(ref: WidgetProviderRef): BindStep =
        withContext(io) {
            val manager = manager ?: return@withContext BindStep.Failed(BindError.HOST_FAILED)
            val user = userFor(ref) ?: return@withContext BindStep.Failed(BindError.PROVIDER_GONE)
            val info = findInfo(ref, user) ?: return@withContext BindStep.Failed(BindError.PROVIDER_GONE)
            val id =
                guard("allocate") { host.allocateAppWidgetId() }
                    ?: return@withContext BindStep.Failed(BindError.HOST_FAILED)
            val bound = guard("bind") { manager.bindAppWidgetIdIfAllowed(id, user, info.provider, null) }
            when (bound) {
                null -> {
                    deleteNow(id)
                    BindStep.Failed(BindError.HOST_FAILED)
                }

                true -> {
                    afterBind(id)
                }

                false -> {
                    BindStep.NeedsConsent(id)
                }
            }
        }

    override suspend fun onConsentResult(
        id: Int,
        ok: Boolean,
    ): BindStep =
        withContext(io) {
            if (!ok) {
                deleteNow(id)
                BindStep.Failed(BindError.DENIED)
            } else {
                afterBind(id)
            }
        }

    override suspend fun onConfigureResult(
        id: Int,
        ok: Boolean,
    ): BindStep =
        withContext(io) {
            val info = if (ok) boundInfo(id) else null
            if (info == null) {
                deleteNow(id)
                BindStep.Failed(BindError.CONFIGURE_CANCELLED)
            } else {
                BindStep.Bound(id)
            }
        }

    /** After a bind: the configure activity unless the provider is both configuration-optional and reconfigurable. */
    private fun afterBind(id: Int): BindStep {
        val info = boundInfo(id)
        if (info == null) {
            deleteNow(id)
            return BindStep.Failed(BindError.DENIED)
        }
        return if (needsConfigure(info)) BindStep.NeedsConfigure(id) else BindStep.Bound(id)
    }

    override suspend fun delete(id: Int) = withContext(io) { deleteNow(id) }

    private fun deleteNow(id: Int) {
        views.remove(id)
        infos.remove(id)
        guard("delete") { host.deleteAppWidgetId(id) }
    }

    override suspend fun isBound(id: Int): Boolean =
        withContext(io) {
            val ids = guard("host ids") { host.appWidgetIds } ?: return@withContext false
            id in ids && boundInfo(id) != null
        }

    override suspend fun hostIds(): List<Int> =
        withContext(io) {
            guard("host ids") { host.appWidgetIds.toList() }.orEmpty()
        }

    override suspend fun minHeightDp(id: Int): Int? = withContext(io) { boundInfo(id)?.let { toDp(it.minHeight) } }

    override fun consentRequest(
        id: Int,
        ref: WidgetProviderRef,
    ): Any? {
        val component = ComponentName.unflattenFromString(ref.component) ?: return null
        val user = userFor(ref) ?: return null
        return Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, component)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, user)
    }

    override fun startConfigure(
        activity: Any,
        id: Int,
        requestCode: Int,
    ): Boolean {
        val a = activity as? Activity ?: return false
        return try {
            host.startAppWidgetConfigureActivityForResult(a, id, 0, requestCode, null)
            true
        } catch (e: ActivityNotFoundException) {
            logger.w(TAG, "no configure activity", e)
            false
        } catch (e: RuntimeException) {
            logger.w(TAG, "configure activity could not start", e)
            false
        }
    }

    override fun hostView(id: Int): WidgetViewHandle? {
        views[id]?.let { return WidgetViewHandle(id, it) }
        val info = infos[id] ?: guard("info") { manager?.getAppWidgetInfo(id) } ?: return null
        val view =
            try {
                host.createView(appContext, id, info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "widget view could not be created", e)
                return null
            }
        views[id] = view
        return WidgetViewHandle(id, view)
    }

    override fun startListening() {
        guard("start listening") { host.startListening() }
    }

    override fun stopListening() {
        guard("stop listening") { host.stopListening() }
    }

    private fun boundInfo(id: Int): AppWidgetProviderInfo? =
        guard("info") { manager?.getAppWidgetInfo(id) }.also { if (it != null) infos[id] = it else infos.remove(id) }

    private fun profiles(): List<UserHandle> =
        guard("profiles") {
            users.userProfiles
        }.orEmpty().ifEmpty { listOf(Process.myUserHandle()) }

    private fun userFor(ref: WidgetProviderRef): UserHandle? =
        guard("user") {
            users.getUserForSerialNumber(ref.userSerial)
        }

    private fun findInfo(
        ref: WidgetProviderRef,
        user: UserHandle,
    ): AppWidgetProviderInfo? {
        val component = ComponentName.unflattenFromString(ref.component) ?: return null
        return guard("providers") { manager?.getInstalledProvidersForProfile(user) }
            ?.firstOrNull { it.provider == component }
    }

    private fun kindOf(user: UserHandle): ProfileKind {
        if (user == Process.myUserHandle()) return ProfileKind.MAIN
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return ProfileKind.WORK
        val type =
            guard("user type") {
                appContext.getSystemService(LauncherApps::class.java).getLauncherUserInfo(user)?.userType
            }
        return when (type) {
            UserManager.USER_TYPE_PROFILE_MANAGED -> ProfileKind.WORK
            UserManager.USER_TYPE_PROFILE_PRIVATE -> ProfileKind.PRIVATE
            else -> ProfileKind.OTHER
        }
    }

    private fun toDp(px: Int): Int = (px / density).roundToInt().coerceAtLeast(1)

    private fun <T> guard(
        what: String,
        block: () -> T,
    ): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "$what failed", e)
            null
        }

    /** Draws [drawable] scaled to fit [maxW] × [maxH], keeping its aspect ratio. */
    private fun render(
        drawable: Drawable,
        maxW: Int,
        maxH: Int,
    ): IconImage? =
        guard("render") {
            val w0 = drawable.intrinsicWidth.takeIf { it > 0 } ?: maxW
            val h0 = drawable.intrinsicHeight.takeIf { it > 0 } ?: maxH
            val scale = minOf(maxW.toFloat() / w0, maxH.toFloat() / h0)
            val w = max(1, (w0 * scale).roundToInt())
            val h = max(1, (h0 * scale).roundToInt())
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(Canvas(bitmap))
            IconImage(bitmap, w, h)
        }

    companion object {
        /** Our `AppWidgetHost` ID; constant and unique within the package (ANDROID_PLATFORM §7). */
        const val HOST_ID = 1

        private const val TAG = "WidgetHost"
        private const val FEATURE_RECONFIGURABLE = 1
        private const val FEATURE_HIDE_FROM_PICKER = 2
        private const val FEATURE_CONFIGURATION_OPTIONAL = 4

        /** ANDROID_PLATFORM §7: configure unless the widget is both configuration-optional (31) and reconfigurable (28). */
        fun needsConfigure(info: AppWidgetProviderInfo): Boolean {
            if (info.configure == null) return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            val features = info.widgetFeatures
            val optional = features and FEATURE_CONFIGURATION_OPTIONAL != 0 && features and FEATURE_RECONFIGURABLE != 0
            return !optional
        }

        private fun hiddenFromPicker(info: AppWidgetProviderInfo): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.widgetFeatures and FEATURE_HIDE_FROM_PICKER != 0
    }
}
