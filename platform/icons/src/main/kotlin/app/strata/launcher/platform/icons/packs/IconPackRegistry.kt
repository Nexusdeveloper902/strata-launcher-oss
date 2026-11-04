package app.strata.launcher.platform.icons.packs

import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.model.IconPackInfo
import app.strata.launcher.core.model.IconPacks
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovered icon packs and their parsed `appfilter.xml`, cached in memory per (pack, versionCode) (ARCHITECTURE §9).
 * Discovery and parsing run on [worker] (serial, I/O allowed); lookups ([mapping]) are lock-free map reads, safe on
 * main. A pack whose file breaks a limit is listed with `usable = false` and never used (NFR-COMPAT-04, T-SEC-06).
 *
 * Discovery publishes the installed list first (so the theme's pack is "installed" without waiting for the parse)
 * and the parse results after; [parseGeneration] is part of every icon cache key, so an icon rendered before its
 * pack was parsed is never served afterwards.
 */
class IconPackRegistry(
    private val source: IconPackSource,
    private val worker: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {
    private class Parsed(val versionCode: Long, val filter: AppFilter?, val problem: PackProblem?)

    private val parsed = ConcurrentHashMap<String, Parsed>()
    private val state = MutableStateFlow(IconPacks())
    private val revisionFlow = MutableStateFlow(0L)

    val packs: StateFlow<IconPacks> = state.asStateFlow()

    /** Bumped on every change that can change a rendered icon. */
    val revision: StateFlow<Long> = revisionFlow.asStateFlow()

    fun refresh() {
        scope.launch(worker) { refreshNow() }
    }

    /** Discovers and parses on the caller's thread (the worker, or a test). */
    fun refreshNow() {
        val candidates =
            try {
                source.discover()
            } catch (e: RuntimeException) {
                logger.w(TAG, "pack discovery failed", e)
                emptyList()
            }
        val names = candidates.mapTo(HashSet()) { it.packageName }
        val removed = parsed.keys.filter { it !in names }
        removed.forEach { parsed.remove(it) }
        val stale = candidates.filter { parsed[it.packageName]?.versionCode != it.versionCode }
        stale.forEach { (source as? PackageManagerIconPackSource)?.forget(it.packageName) }
        // Publish the list first: packs pending a parse count as usable until the parse says otherwise.
        publish(candidates)
        if (removed.isNotEmpty() || stale.isNotEmpty()) revisionFlow.update { it + 1 }
        if (stale.isEmpty()) return
        for (c in stale) {
            val result =
                try {
                    source.appFilter(c.packageName)
                } catch (e: RuntimeException) {
                    AppFilterResult.Rejected(PackProblem.MALFORMED)
                }
            parsed[c.packageName] =
                when (result) {
                    is AppFilterResult.Ok -> Parsed(c.versionCode, result.filter, null)
                    is AppFilterResult.Rejected -> Parsed(c.versionCode, null, result.problem)
                }
            if (result is AppFilterResult.Rejected) {
                logger.w(
                    TAG,
                    "icon pack ${c.packageName} ignored: ${result.problem}",
                )
            }
        }
        publish(candidates)
        revisionFlow.update { it + 1 }
    }

    private fun publish(candidates: List<PackCandidate>) {
        state.value =
            IconPacks(
                packs =
                    candidates.map { c ->
                        val p = parsed[c.packageName]?.takeIf { it.versionCode == c.versionCode }
                        IconPackInfo(
                            packageName = c.packageName,
                            label = c.label,
                            versionCode = c.versionCode,
                            usable = p == null || p.filter != null,
                            mappedCount = p?.filter?.components?.size ?: 0,
                        )
                    },
                discovered = true,
            )
    }

    /** The parsed filter of a usable [pack], or null (missing, unusable or not parsed yet). */
    fun mapping(pack: String): AppFilter? = parsed[pack]?.filter

    /** The problem that made [pack] unusable, or null. */
    fun problem(pack: String): PackProblem? = parsed[pack]?.problem

    /** Cache-key part of [pack]: version and parse state. */
    fun keyPart(pack: String): String {
        val p = parsed[pack] ?: return "$pack:pending"
        return "$pack:${p.versionCode}:${if (p.filter != null) "ok" else "bad"}"
    }

    fun isUsable(pack: String): Boolean = parsed[pack]?.filter != null

    private companion object {
        const val TAG = "IconPacks"
    }
}
