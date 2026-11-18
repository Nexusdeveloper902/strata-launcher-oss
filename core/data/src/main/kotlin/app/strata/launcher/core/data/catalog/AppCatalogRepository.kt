package app.strata.launcher.core.data.catalog

import app.strata.launcher.core.common.Clock
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.data.LazyResource
import app.strata.launcher.core.data.db.AppSnapshotEntity
import app.strata.launcher.core.data.db.StrataDatabase
import app.strata.launcher.core.model.AppEntry
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.Catalog
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.platformapi.apps.AppCatalog
import app.strata.launcher.core.platformapi.apps.AppCatalogSource
import app.strata.launcher.core.platformapi.apps.PackageChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single writer of the `app_snapshot` cache (ARCHITECTURE §4). [start] emits the persisted snapshot
 * first (FR-LIFE-01), then serves reconcile requests on [catalogDispatcher]: full passes (start, `onStart`,
 * launch failure, profile changes) and per-package passes (`LauncherApps.Callback`).
 * Override records of vanished apps are kept 30 days (`missing_since`) and then purged (FR-LIFE-03).
 *
 * Per-package passes never wait for a full one (FB-009, NFR-PERF-06): in a batch they run first, and while a full
 * pass is loading (on the source's I/O dispatcher, which leaves [catalogDispatcher] free) new per-package requests
 * are still served. Packages handled during the load keep their per-package result when the full pass commits, so
 * a load read before an uninstall cannot bring the app back.
 */
class AppCatalogRepository(
    private val database: LazyResource<StrataDatabase>,
    private val source: AppCatalogSource,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val catalogDispatcher: CoroutineDispatcher,
    private val logger: Logger,
    /** Collation bucket stored in `section_key` at write time. */
    private val sectionKeyOf: (String) -> String = { "" },
) : AppCatalog {
    private val state = MutableStateFlow(Catalog.EMPTY)
    private val loaded = MutableStateFlow(false)
    private val started = AtomicBoolean(false)
    private val requests = Channel<Request>(Channel.UNLIMITED)

    override val catalog: StateFlow<Catalog> = state

    /** True once the snapshot has been read (the first list frame can render). */
    val snapshotLoaded: StateFlow<Boolean> = loaded

    /** Idempotent. Reads the snapshot, then subscribes to platform changes and serves reconcile requests. */
    fun start(): Job? {
        if (!started.compareAndSet(false, true)) return null
        return scope.launch(catalogDispatcher) {
            // Subscribe before reading the snapshot, so no event is lost; events queue until the snapshot is out.
            launch(start = CoroutineStart.UNDISPATCHED) {
                source.changes.collect { change ->
                    requests.send(
                        when (change) {
                            PackageChange.All -> {
                                Request.Full
                            }

                            is PackageChange.Packages -> {
                                Request.Packages(
                                    change.packageNames.toSet(),
                                    change.userSerial,
                                )
                            }
                        },
                    )
                }
            }
            loadSnapshot()
            var pending: Request? = null
            while (true) {
                val batch = drain(pending ?: requests.receive())
                pending = null
                runCatchingLogged("reconcile") { packagesPass(batch.filterIsInstance<Request.Packages>()) }
                if (batch.any { it == Request.Full }) {
                    runCatchingLogged("reconcile") { if (fullPass()) pending = Request.Full }
                }
            }
        }
    }

    override fun requestReconcile() {
        requests.trySend(Request.Full)
    }

    /** Runs one full reconcile now (tests and the first start). */
    suspend fun reconcileNow() {
        fullPass()
    }

    private suspend fun loadSnapshot() {
        runCatchingLogged("snapshot") {
            val rows = database.get().snapshotDao().all()
            state.value = Catalog(rows.map { it.toEntry() }, fromSnapshot = true)
        }
        loaded.value = true
    }

    private fun drain(first: Request): List<Request> {
        val batch = mutableListOf(first)
        while (true) batch += requests.tryReceive().getOrNull() ?: break
        return batch
    }

    private suspend fun packagesPass(requests: List<Request.Packages>) {
        val bySerial = requests.groupBy { it.userSerial }
        for ((serial, reqs) in bySerial) {
            val packages = reqs.flatMap { it.packages }.toSet()
            val loadedEntries = packages.flatMap { source.loadPackage(it, serial) }
            val result = Reconciler.packages(state.value.entries, packages, serial, loadedEntries, clock.nowMillis())
            commit(result, loadedSerials = setOf(serial), fromSnapshot = state.value.fromSnapshot)
        }
    }

    /**
     * One full pass. While `loadAll` runs, per-package requests are served and remembered; a full request that
     * arrives meanwhile is answered with true (run another full pass: this load may predate its cause).
     */
    private suspend fun fullPass(): Boolean =
        coroutineScope {
            val load = async { source.loadAll() }
            val touched = HashSet<Pair<Long, String>>()
            var again = false
            while (!load.isCompleted) {
                val next =
                    select<Request?> {
                        load.onAwait { null }
                        requests.onReceive { it }
                    } ?: break
                val batch = drain(next)
                if (batch.any { it == Request.Full }) again = true
                val packages = batch.filterIsInstance<Request.Packages>()
                runCatchingLogged("reconcile") { packagesPass(packages) }
                packages.forEach { r -> r.packages.forEach { touched += r.userSerial to it } }
            }
            val loaded = load.await()
            val current = state.value.entries

            fun isTouched(e: AppEntry) = (e.key.userSerial to e.packageName) in touched
            val platform =
                if (touched.isEmpty()) {
                    loaded.entries
                } else {
                    loaded.entries.filterNot(::isTouched) + current.filter(::isTouched)
                }
            val result = Reconciler.full(current, platform, clock.nowMillis())
            commit(result, loaded.loadedSerials, fromSnapshot = false)
            again
        }

    private suspend fun commit(
        result: ReconcileResult,
        loadedSerials: Set<Long>,
        fromSnapshot: Boolean,
    ) {
        // Publish first: the UI must not wait for the disk (≤ 1 s change propagation, NFR-PERF-06).
        state.value = Catalog(result.entries, fromSnapshot = fromSnapshot)
        val db = database.get()
        if (result.changed) {
            db.snapshotDao().apply(
                upserts = result.upserts.filter { it.profileKind != ProfileKind.PRIVATE }.map { it.toRow() },
                deletes = result.removed.map { it.component to it.userSerial },
            )
        }
        val now = clock.nowMillis()
        val present = result.entries.mapTo(HashSet()) { it.key }
        val overrides = db.overrideDao().all()
        val toClear = overrides.filter { it.missingSince != null && AppKey(it.component, it.userSerial) in present }
        val toMark =
            overrides.filter {
                it.missingSince == null && it.userSerial in loadedSerials &&
                    AppKey(it.component, it.userSerial) !in present
            }
        val withRow = overrides.mapTo(HashSet()) { AppKey(it.component, it.userSerial) }
        val markers =
            db
                .favoriteDao()
                .all()
                .map { AppKey(it.component, it.userSerial) }
                .filter { it !in withRow && it.userSerial in loadedSerials && it !in present }
        db.overrideDao().reconcile(
            present = toClear.map { it.component to it.userSerial },
            missing = toMark.map { it.component to it.userSerial },
            now = now,
            purgeCutoff = now - RETENTION_MS,
            markers = markers.map { it.component to it.userSerial },
        )
    }

    private suspend fun runCatchingLogged(
        what: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "catalog $what failed", e)
        }
    }

    private fun AppSnapshotEntity.toEntry() =
        AppEntry(
            key = AppKey(component, userSerial),
            packageName = packageName,
            systemLabel = systemLabel,
            profileKind = ProfileKindCodes.decode(profileKind),
            flags = flags,
            firstSeenAt = firstSeenAt,
            lastUpdateTime = lastUpdateTime,
        )

    private fun AppEntry.toRow() =
        AppSnapshotEntity(
            component = key.component,
            userSerial = key.userSerial,
            systemLabel = systemLabel,
            packageName = packageName,
            profileKind = ProfileKindCodes.encode(profileKind),
            flags = flags,
            firstSeenAt = firstSeenAt,
            lastUpdateTime = lastUpdateTime,
            sectionKey = sectionKeyOf(systemLabel),
        )

    private sealed interface Request {
        data object Full : Request

        data class Packages(val packages: Set<String>, val userSerial: Long) : Request
    }

    companion object {
        private const val TAG = "Catalog"

        /** Records of removed apps are kept 30 days (FR-LIFE-03). */
        const val RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000
    }
}

/** `app_snapshot.profile_kind` codes. PRIVATE is never stored. */
internal object ProfileKindCodes {
    fun encode(kind: ProfileKind): Int =
        when (kind) {
            ProfileKind.MAIN -> 0
            ProfileKind.WORK -> 1
            ProfileKind.PRIVATE -> 2
            ProfileKind.OTHER -> 3
        }

    fun decode(code: Int): ProfileKind =
        when (code) {
            0 -> ProfileKind.MAIN
            1 -> ProfileKind.WORK
            2 -> ProfileKind.PRIVATE
            else -> ProfileKind.OTHER
        }
}
