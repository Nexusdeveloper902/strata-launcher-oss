package app.strata.launcher.core.platformapi.icons

import app.strata.launcher.core.model.IconCoverageEntry
import app.strata.launcher.core.model.IconImage
import app.strata.launcher.core.model.IconPacks
import app.strata.launcher.core.model.IconRequest
import app.strata.launcher.core.model.IconResult
import app.strata.launcher.core.model.IconStyle
import app.strata.launcher.core.model.PackDrawables
import app.strata.launcher.core.model.VisibleApp
import kotlinx.coroutines.flow.StateFlow

/**
 * Icon loader and render pipeline (ARCHITECTURE §3, §9), implemented in `:platform:icons`. Main-safe: rendering runs
 * on the provider's own dispatchers. Icon packs (ANDROID_PLATFORM §11.1) are read as untrusted input.
 */
interface IconProvider {
    /** The L1 entry, if present. Cheap; safe on main (first frame without a fade). */
    fun cached(req: IconRequest): IconImage?

    /** L1 → L2 → source. Main-safe; never throws (failures give [IconResult.Monogram]). */
    suspend fun icon(req: IconRequest): IconResult

    /** `onTrimMemory`: drops the L1 cache (fully when [critical], else half). */
    fun trim(critical: Boolean)

    /** Installed icon packs (ADW convention); unusable packs are listed with `usable = false`. */
    val iconPacks: StateFlow<IconPacks>

    /**
     * Bumped whenever the same [IconRequest] may now render differently (a pack was parsed, installed, updated or
     * removed). Rows re-request their icon when it changes.
     */
    val revision: StateFlow<Long>

    /** Starts pack discovery and the package listener; idempotent. */
    fun start()

    /** Re-discovers packs (activity `onStart`, package events). */
    fun refreshPacks()

    /** S-ICON: the drawables [pack] offers, with its mapping for [component] first. Null when unusable or missing. */
    suspend fun packDrawables(
        pack: String,
        component: String?,
    ): PackDrawables?

    /** S-ICON grid cell: one drawable of [pack] at [sizePx], drawn as supplied. */
    suspend fun packIcon(
        pack: String,
        drawable: String,
        sizePx: Int,
        densityDpi: Int,
    ): IconResult

    /**
     * FR-SET-07 (ARCHITECTURE §9.1): the icon source of each of [apps] under [style], decided by the same precedence
     * and reads as the renderer, so the coverage tool cannot disagree with what home draws. Private-space apps are
     * skipped. Main-safe; the result is memory only.
     */
    suspend fun coverage(
        apps: List<VisibleApp>,
        style: IconStyle,
    ): List<IconCoverageEntry>
}
