package app.strata.launcher.core.platformapi.system

import kotlinx.coroutines.flow.StateFlow

/** `WallpaperColors` of the system wallpaper (API 27+). Colours are `0xFFRRGGBB`. */
data class WallpaperColorsModel(
    val primary: Int,
    val secondary: Int?,
    val tertiary: Int?,
    /** `HINT_SUPPORTS_DARK_TEXT` (API 31+; false below). */
    val supportsDarkText: Boolean,
)

/**
 * The system wallpaper's colours (ARCHITECTURE §3, ANDROID_PLATFORM §12). `getWallpaperColors` is IPC, so
 * the adapter reads it off the main thread. [colors] is null below API 27, before the first read, and when
 * the platform has none (some live wallpapers). [loaded] turns true after the first read attempt.
 */
interface WallpaperColorsSource {
    val colors: StateFlow<WallpaperColorsModel?>
    val loaded: StateFlow<Boolean>

    /** Starts listening (`addOnColorsChangedListener`) and reads once; idempotent. */
    fun start()
}

/** The eight Material roles THEME_SYSTEM §5.2 maps onto Strata roles, as `0xFFRRGGBB`. */
data class DynamicRoles(
    val surface: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    val onPrimary: Int,
    val outlineVariant: Int,
)

data class DynamicColorsModel(val light: DynamicRoles, val dark: DynamicRoles)

/** The platform's dynamic colour scheme (API 31+); [read] returns null below 31. Reads resources only. */
fun interface DynamicColorsSource {
    fun read(): DynamicColorsModel?
}

/** `Settings.Global.ANIMATOR_DURATION_SCALE` and `ValueAnimator.areAnimatorsEnabled()` (THEME_SYSTEM §4.11). */
data class AnimatorScale(val durationScale: Float, val enabled: Boolean)

fun interface AnimatorScaleSource {
    /** Blocking (a settings-provider read): call off the main thread. */
    fun read(): AnimatorScale
}
