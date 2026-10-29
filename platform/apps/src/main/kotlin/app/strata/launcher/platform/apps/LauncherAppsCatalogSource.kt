package app.strata.launcher.platform.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Trace
import android.os.UserHandle
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.AppEntry
import app.strata.launcher.core.model.AppFlags
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.platformapi.apps.AppCatalogSource
import app.strata.launcher.core.platformapi.apps.CatalogLoad
import app.strata.launcher.core.platformapi.apps.PackageChange
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [AppCatalogSource] over `LauncherApps` (ANDROID_PLATFORM §3.1, FR-APPS-01, FR-LIST-04). Visibility comes
 * from the manifest's `MAIN`/`LAUNCHER` `<queries>` entry; no `QUERY_ALL_PACKAGES`. Callbacks run on the
 * `strata-platform` thread and only re-emit (ARCHITECTURE §6). Private-space profiles are skipped until M6;
 * profiles in quiet mode are skipped (their rows return when the profile is available again).
 */
class LauncherAppsCatalogSource(
    context: Context,
    private val io: CoroutineDispatcher,
    private val logger: Logger,
) : AppCatalogSource {
    private val app = context.applicationContext
    private val resolver by lazy { ProfileResolver(app) }

    override suspend fun loadAll(): CatalogLoad =
        withContext(io) {
            val entries = ArrayList<AppEntry>()
            val serials = HashSet<Long>()
            for (user in resolver.profiles()) {
                val kind = resolver.kindOf(user)
                if (kind == ProfileKind.PRIVATE || resolver.quiet(user)) continue
                val serial = resolver.serialOf(user)
                val list = activities(null, user) ?: continue
                serials += serial
                list.mapTo(entries) { it.toEntry(serial, kind) }
            }
            CatalogLoad(entries, serials)
        }

    override suspend fun loadPackage(
        packageName: String,
        userSerial: Long,
    ): List<AppEntry> =
        withContext(io) {
            val user = resolver.userOf(userSerial) ?: return@withContext emptyList()
            val kind = resolver.kindOf(user)
            if (kind == ProfileKind.PRIVATE || resolver.quiet(user)) return@withContext emptyList()
            activities(packageName, user).orEmpty().map { it.toEntry(userSerial, kind) }
        }

    override val changes: Flow<PackageChange> =
        callbackFlow {
            val callback =
                object : LauncherApps.Callback() {
                    private fun send(
                        packages: Array<out String>?,
                        user: UserHandle,
                    ) {
                        if (packages.isNullOrEmpty()) return
                        trySend(PackageChange.Packages(packages.toList(), resolver.serialOf(user)))
                    }

                    override fun onPackageAdded(
                        packageName: String,
                        user: UserHandle,
                    ) {
                        // Trace marker for T-PERF-10 (change propagation, NFR-PERF-06).
                        Trace.beginSection(TRACE_PACKAGE_ADDED)
                        send(arrayOf(packageName), user)
                        Trace.endSection()
                    }

                    override fun onPackageRemoved(
                        packageName: String,
                        user: UserHandle,
                    ) = send(arrayOf(packageName), user)

                    override fun onPackageChanged(
                        packageName: String,
                        user: UserHandle,
                    ) = send(arrayOf(packageName), user)

                    override fun onPackagesAvailable(
                        packageNames: Array<out String>?,
                        user: UserHandle,
                        replacing: Boolean,
                    ) = send(packageNames, user)

                    override fun onPackagesUnavailable(
                        packageNames: Array<out String>?,
                        user: UserHandle,
                        replacing: Boolean,
                    ) = send(packageNames, user)

                    override fun onPackagesSuspended(
                        packageNames: Array<out String>?,
                        user: UserHandle,
                    ) = send(packageNames, user)

                    override fun onPackagesUnsuspended(
                        packageNames: Array<out String>?,
                        user: UserHandle,
                    ) = send(packageNames, user)
                }
            resolver.launcherApps.registerCallback(callback, PlatformThread.handler)
            val receiver = registerProfileReceiver(app) { trySend(PackageChange.All) }
            awaitClose {
                resolver.launcherApps.unregisterCallback(callback)
                runCatching { app.unregisterReceiver(receiver) }
            }
        }

    private fun activities(
        packageName: String?,
        user: UserHandle,
    ): List<LauncherActivityInfo>? =
        try {
            resolver.launcherApps.getActivityList(packageName, user)
        } catch (e: SecurityException) {
            logger.w(TAG, "profile not accessible", e)
            null
        } catch (e: IllegalStateException) {
            // User locked or being removed.
            logger.w(TAG, "profile not ready", e)
            null
        }

    private fun LauncherActivityInfo.toEntry(
        serial: Long,
        kind: ProfileKind,
    ): AppEntry {
        val info = applicationInfo
        return AppEntry(
            key = AppKey(componentName.flattenToString(), serial),
            packageName = componentName.packageName,
            systemLabel = label?.toString().orEmpty(),
            profileKind = kind,
            flags = flagsOf(info, componentName.className),
            lastUpdateTime = runCatching { File(info.sourceDir).lastModified() }.getOrDefault(0L),
        )
    }

    private fun flagsOf(
        info: ApplicationInfo,
        className: String,
    ): Int {
        var flags = 0
        if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) flags = flags or AppFlags.SYSTEM
        if (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0) flags = flags or AppFlags.UPDATED_SYSTEM
        if (info.flags and ApplicationInfo.FLAG_SUSPENDED != 0) flags = flags or AppFlags.SUSPENDED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && info.isArchived) {
            flags = flags or AppFlags.ARCHIVED
        }
        if (className == SYNTHESIZED_DETAILS_CLASS) flags = flags or AppFlags.SYNTHESIZED
        return flags
    }

    companion object {
        private const val TAG = "Catalog"
        const val TRACE_PACKAGE_ADDED = "strata.onPackageAdded"

        /** Activity the platform uses for synthesized "app details" entries (Inferred from AOSP; display only). */
        private const val SYNTHESIZED_DETAILS_CLASS = "android.app.AppDetailsActivity"
    }
}
