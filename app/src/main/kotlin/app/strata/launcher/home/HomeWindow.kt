package app.strata.launcher.home

import android.content.pm.ActivityInfo
import app.strata.launcher.core.model.IconImage
import app.strata.launcher.core.model.IconRequest
import app.strata.launcher.core.model.IconResult
import app.strata.launcher.core.platformapi.icons.IconProvider
import app.strata.launcher.core.ui.IconLoader

/** Window rules of the home activity (FR-HOME-08), pure for unit tests. */
object HomeWindow {
    /** Below this smallest width the device is a phone (VISUAL_SPEC §8, FR-HOME-08). */
    const val TABLET_MIN_SW_DP = 600

    /**
     * FR-HOME-08: phones stay portrait unless "Allow rotation" is on; at sw ≥ 600 dp home always follows the
     * orientation (no letterboxing, T-HOME-12).
     */
    fun orientation(
        smallestWidthDp: Int,
        allowRotation: Boolean,
    ): Int =
        if (smallestWidthDp < TABLET_MIN_SW_DP && !allowRotation) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
}

/** `:core:ui`'s [IconLoader] over `:platform:icons` (ARCHITECTURE §2.1: `:core:ui` cannot see platform-api). */
class ProviderIconLoader(private val provider: IconProvider) : IconLoader {
    override fun cached(req: IconRequest): IconImage? = provider.cached(req)

    override suspend fun load(req: IconRequest): IconResult = provider.icon(req)
}
