package app.strata.launcher.platform.widgets

import android.app.Activity
import android.app.ActivityOptions
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
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.SizeF
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
import kotlinx.coroutines.NonCancellable
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
 * (ARCHITECTURE §10); `stopListening` leaves them registered with the host (AOSP 8.0+ does not clear its views
 * there), so `startListening` brings each one up to date. Every platform or provider exception is caught, and so is
 * an `OutOfMemoryError` while decoding a preview: a broken widget never takes home down.
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

    /** Provider info by bound ID, filled off main ([isBound], binds) so [hostView] needs no binder call. */
    private val infos = ConcurrentHashMap<Int, AppWidgetProviderInfo>()

    /**
     * Provider info from the last [providers] call. Previews and icons read it instead of listing every provider again
     * for each row: many rows at once each fetching the full list ran the binder buffer out on API 36
     * (DeadObjectException in CI run 43161519075). Binding always reads the current list.
     */
    private val listed = ConcurrentHashMap<WidgetProviderRef, AppWidgetProviderInfo>()

    /** IDs allocated by a bind flow and not placed yet: the orphan sweep leaves them alone. */
    private val inFlight: MutableSet<Int> = ConcurrentHashMap.newKeySet()

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
            val seen = HashMap<WidgetProviderRef, AppWidgetProviderInfo>()
            for (user in profiles()) {
                // P-5: nothing of the private space is ever persisted; a profile of unknown type is skipped (fail closed).
                val kind = kindOf(user)?.takeIf { it != ProfileKind.PRIVATE } ?: continue
                val serial = guard("serial") { users.getSerialNumberForUser(user) } ?: continue
                val infos = guard("providers") { manager.getInstalledProvidersForProfile(user) } ?: continue
                for (info in infos) {
                    if (hiddenFromPicker(info)) continue
                    val appLabel =
                        guard("app label") {
                            pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
                        } ?: info.provider.packageName
                    val ref = WidgetProviderRef(info.provider.flattenToString(), serial)
                    seen[ref] = info
                    out +=
                        WidgetProviderModel(
                            ref = ref,
                            label = guard("label") { info.loadLabel(pm) } ?: info.provider.className,
                            appLabel = appLabel,
                            profileKind = kind,
                            minWidthDp = toDp(info.minWidth),
                            minHeightDp = toDp(info.minHeight),
                            needsConfigure = needsConfigure(info),
                        )
                }
            }
            listed.clear()
            listed.putAll(seen)
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
                    decode("generated preview") {
                        manager?.getWidgetPreview(component, user, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
                    }
                if (generated != null) return@withContext WidgetPreview.Generated(generated)
            }
            val info = listed[ref] ?: findInfo(ref, user) ?: return@withContext WidgetPreview.None
            val dpi = appContext.resources.displayMetrics.densityDpi
            val drawable =
                decode("preview image") { info.loadPreviewImage(appContext, dpi) }
                    ?: decode("icon") { info.loadIcon(appContext, dpi) }
                    ?: return@withContext WidgetPreview.None
            render(drawable, maxWidthPx, maxHeightPx)?.let { WidgetPreview.Image(it) } ?: WidgetPreview.None
        }

    override suspend fun icon(
        ref: WidgetProviderRef,
        sizePx: Int,
    ): IconImage? =
        withContext(io) {
            val user = userFor(ref) ?: return@withContext null
            val info = listed[ref] ?: findInfo(ref, user) ?: return@withContext null
            val drawable =
                decode("icon") { info.loadIcon(appContext, appContext.resources.displayMetrics.densityDpi) }
                    ?: return@withContext null
            render(drawable, sizePx, sizePx)
        }

    override suspend fun allocateAndBind(ref: WidgetProviderRef): BindStep {
        var allocated = INVALID_ID
        try {
            return bindNew(ref) { allocated = it }
        } catch (e: CancellationException) {
            // The caller went away during the call (picker closed): the ID it would have received is freed here.
            if (allocated != INVALID_ID) withContext(NonCancellable + io) { deleteNow(allocated) }
            throw e
        }
    }

    private suspend fun bindNew(
        ref: WidgetProviderRef,
        onAllocated: (Int) -> Unit,
    ): BindStep =
        withContext(io) {
            val manager = manager ?: return@withContext BindStep.Failed(BindError.HOST_FAILED)
            val user = userFor(ref) ?: return@withContext BindStep.Failed(BindError.PROVIDER_GONE)
            val info = findInfo(ref, user) ?: return@withContext BindStep.Failed(BindError.PROVIDER_GONE)
            val id =
                guard("allocate") { host.allocateAppWidgetId() }
                    ?: return@withContext BindStep.Failed(BindError.HOST_FAILED)
            inFlight += id
            onAllocated(id)
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
        inFlight.remove(id)
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

    override fun inFlightIds(): Set<Int> = inFlight.toSet()

    override fun settled(id: Int) {
        inFlight.remove(id)
    }

    override suspend fun minHeightDp(id: Int): Int? = withContext(io) { boundInfo(id)?.let { toDp(it.minHeight) } }

    override suspend fun updateSize(
        id: Int,
        widthDp: Float,
        heightDp: Float,
    ) {
        withContext(io) {
            val view = views[id] ?: return@withContext
            // AOSP AppWidgetHostView: reads the view's padding and makes binder calls only, so it may run off main.
            guard("size update") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    view.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp, heightDp)))
                } else {
                    val w = widthDp.toInt()
                    val h = heightDp.toInt()
                    @Suppress("DEPRECATION")
                    view.updateAppWidgetSize(null, w, h, w, h)
                }
            }
        }
    }

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
            host.startAppWidgetConfigureActivityForResult(a, id, 0, requestCode, configureOptions())
            true
        } catch (e: ActivityNotFoundException) {
            logger.w(TAG, "no configure activity", e)
            false
        } catch (e: RuntimeException) {
            logger.w(TAG, "configure activity could not start", e)
            false
        }
    }

    /**
     * API 34+: `startAppWidgetConfigureActivityForResult` sends an IntentSender whose creator is the provider's app,
     * which is usually in the background. Since Android 14 the sender's (our foreground) right to start activities only
     * applies when the sender opts in; without it the start is aborted and RESULT_CANCELED arrives at once, so the
     * configure screen never appears (CI run 43204723608, API 34). ANDROID_PLATFORM §4: `ALLOW_IF_VISIBLE` on 36+
     * (`ALLOWED` is deprecated there), `ALLOWED` on 34–35. The picker is visible whenever this is called.
     */
    private fun configureOptions(): Bundle? {
        val mode =
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA -> {
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                    @Suppress("DEPRECATION")
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }

                else -> {
                    return null
                }
            }
        return ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle()
    }

    /**
     * Main only. Uses the provider info [isBound] published (the widget area shows a widget only after that check),
     * so no binder call is made here; without it the view is not created and the error tile is shown.
     */
    override fun hostView(id: Int): WidgetViewHandle? {
        views[id]?.let { return WidgetViewHandle(id, it) }
        val info = infos[id] ?: return null
        val view =
            try {
                host.createView(appContext, id, info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "widget view could not be created", e)
                return null
            } catch (e: OutOfMemoryError) {
                logger.w(TAG, "widget view too large", e)
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

    /** The profile of [ref], only when it may be used: never a private space nor a profile of unknown type (P-5). */
    private fun userFor(ref: WidgetProviderRef): UserHandle? {
        val user = guard("user") { users.getUserForSerialNumber(ref.userSerial) } ?: return null
        val kind = kindOf(user) ?: return null
        return user.takeIf { kind != ProfileKind.PRIVATE }
    }

    private fun findInfo(
        ref: WidgetProviderRef,
        user: UserHandle,
    ): AppWidgetProviderInfo? {
        val component = ComponentName.unflattenFromString(ref.component) ?: return null
        return guard("providers") { manager?.getInstalledProvidersForProfile(user) }
            ?.firstOrNull { it.provider == component }
    }

    /** MAIN for this user, else [classify] of the launcher user type (read on API 35+ only). */
    private fun kindOf(user: UserHandle): ProfileKind? {
        if (user == Process.myUserHandle()) return ProfileKind.MAIN
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                guard("user type") {
                    appContext.getSystemService(LauncherApps::class.java).getLauncherUserInfo(user)?.userType
                }
            } else {
                null
            }
        return classify(Build.VERSION.SDK_INT, type)
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

    /** [guard] that also survives `OutOfMemoryError`: a provider's preview can be a huge bitmap. */
    private fun <T> decode(
        what: String,
        block: () -> T,
    ): T? =
        try {
            guard(what, block)
        } catch (e: OutOfMemoryError) {
            logger.w(TAG, "$what too large", e)
            null
        }

    /** Draws [drawable] scaled to fit [maxW] × [maxH], keeping its aspect ratio. */
    private fun render(
        drawable: Drawable,
        maxW: Int,
        maxH: Int,
    ): IconImage? =
        decode("render") {
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
        private const val INVALID_ID = 0
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

        /**
         * The kind of a non-main profile, as M6's profile source decides it (ANDROID_PLATFORM §3.2/§3.3): WORK below
         * API 35 (no private space there); on 35+ the launcher user type, and null (skip: fail closed) when unknown.
         */
        fun classify(
            sdk: Int,
            type: String?,
        ): ProfileKind? {
            if (sdk < Build.VERSION_CODES.VANILLA_ICE_CREAM) return ProfileKind.WORK
            return when (type) {
                null -> null
                UserManager.USER_TYPE_PROFILE_MANAGED -> ProfileKind.WORK
                UserManager.USER_TYPE_PROFILE_PRIVATE -> ProfileKind.PRIVATE
                else -> ProfileKind.OTHER
            }
        }

        private fun hiddenFromPicker(info: AppWidgetProviderInfo): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.widgetFeatures and FEATURE_HIDE_FROM_PICKER != 0
    }
}
