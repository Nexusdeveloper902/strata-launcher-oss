package app.strata.launcher.theme

import android.os.Build
import app.strata.launcher.core.model.IconPacks
import app.strata.launcher.core.platformapi.system.AnimatorScale
import app.strata.launcher.core.platformapi.system.AnimatorScaleSource
import app.strata.launcher.core.platformapi.system.DynamicColorsModel
import app.strata.launcher.core.platformapi.system.DynamicColorsSource
import app.strata.launcher.core.platformapi.system.DynamicRoles
import app.strata.launcher.core.platformapi.system.WallpaperColorsModel
import app.strata.launcher.core.platformapi.system.WallpaperColorsSource
import app.strata.launcher.core.theme.color.MaterialRoles
import app.strata.launcher.core.theme.resolve.DynamicSchemeInput
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.WallpaperColorsInput
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collects the platform half of THEME_SYSTEM §2's `platformInputs` (API level, wallpaper colours, dynamic scheme,
 * animator scale) for the theme repository. Every platform read runs on [io]. [inputs] is null until the first
 * read finished, so the first frame waits for real inputs instead of flashing a fallback (§12 step 1).
 * `systemDark` is not set here: the repository resolves both variants.
 */
class PlatformThemeInputs(
    private val wallpaper: WallpaperColorsSource,
    private val dynamic: DynamicColorsSource,
    private val animator: AnimatorScaleSource,
    /** Discovered icon packs (FR-THEME-10/25); inputs wait for the first discovery so no false "missing" shows. */
    private val iconPacks: Flow<IconPacks> = flowOf(IconPacks(discovered = true)),
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val apiLevel: Int = Build.VERSION.SDK_INT,
) {
    private data class Local(val dynamic: DynamicColorsModel?, val animator: AnimatorScale)

    private val started = AtomicBoolean(false)
    private val local = MutableStateFlow<Local?>(null)

    val inputs: Flow<PlatformInputs?> =
        combine(local, wallpaper.colors, wallpaper.loaded, iconPacks) { l, w, loaded, packs ->
            if (l == null || !loaded || !packs.discovered) {
                null
            } else {
                toInputs(l, w).copy(installedIconPacks = packs.usable, unusableIconPacks = packs.unusable)
            }
        }.distinctUntilChanged()

    /** Starts the wallpaper listener and the first read; idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        wallpaper.start()
        refresh()
        // On API 31+ the system scheme is derived from the wallpaper: re-read it when the colours change.
        scope.launch { wallpaper.colors.drop(1).collect { refresh() } }
    }

    /** Re-reads the dynamic scheme and animator scale (configuration change, `onStart`). */
    fun refresh() {
        scope.launch(io) {
            local.value =
                Local(
                    runCatching { dynamic.read() }.getOrNull(),
                    runCatching { animator.read() }.getOrDefault(DEFAULT_SCALE),
                )
        }
    }

    private fun toInputs(
        l: Local,
        w: WallpaperColorsModel?,
    ): PlatformInputs =
        PlatformInputs(
            apiLevel = apiLevel,
            wallpaperColors =
                w?.let {
                    WallpaperColorsInput(
                        it.primary,
                        it.secondary,
                        it.tertiary,
                        it.supportsDarkText,
                    )
                },
            dynamicScheme = l.dynamic?.let { DynamicSchemeInput(roles(it.light), roles(it.dark)) },
            animatorDurationScale = l.animator.durationScale.toDouble(),
            animatorsEnabled = l.animator.enabled,
        )

    private fun roles(r: DynamicRoles): MaterialRoles =
        MaterialRoles(
            surface = r.surface,
            surfaceContainerHigh = r.surfaceContainerHigh,
            surfaceContainerHighest = r.surfaceContainerHighest,
            onSurface = r.onSurface,
            onSurfaceVariant = r.onSurfaceVariant,
            primary = r.primary,
            onPrimary = r.onPrimary,
            outlineVariant = r.outlineVariant,
        )

    private companion object {
        val DEFAULT_SCALE = AnimatorScale(1f, true)
    }
}
