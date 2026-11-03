package app.strata.launcher.core.platformapi.icons

import app.strata.launcher.core.model.IconImage
import app.strata.launcher.core.model.IconRequest
import app.strata.launcher.core.model.IconResult

/**
 * Icon loader and cache (ARCHITECTURE §3, §9), implemented in `:platform:icons`. M3: system icons
 * (`LauncherActivityInfo.getIcon`, badged for work apps) with an L1 memory cache (≤ 1/8 of the memory
 * class) and an L2 disk cache (`cacheDir/icons/`, 32 MB LRU). Icon packs and shapes arrive in M5.
 */
interface IconProvider {
    /** The L1 entry, if present. Cheap; safe on main (first frame without a fade). */
    fun cached(req: IconRequest): IconImage?

    /** L1 → L2 → source. Main-safe; never throws (failures give [IconResult.Monogram]). */
    suspend fun icon(req: IconRequest): IconResult

    /** `onTrimMemory`: drops the L1 cache (fully when [critical], else half). */
    fun trim(critical: Boolean)
}
