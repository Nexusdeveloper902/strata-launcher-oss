package app.strata.launcher.platform.system

import android.animation.ValueAnimator
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import androidx.annotation.ColorRes
import androidx.annotation.RequiresApi
import app.strata.launcher.core.platformapi.system.AnimatorScale
import app.strata.launcher.core.platformapi.system.AnimatorScaleSource
import app.strata.launcher.core.platformapi.system.DynamicColorsModel
import app.strata.launcher.core.platformapi.system.DynamicColorsSource
import app.strata.launcher.core.platformapi.system.DynamicRoles
import app.strata.launcher.core.platformapi.system.WallpaperColorsModel
import app.strata.launcher.core.platformapi.system.WallpaperColorsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [WallpaperColorsSource] over `WallpaperManager` (ANDROID_PLATFORM §12). The listener and every read run on a
 * private handler thread: `getWallpaperColors` is IPC and must not run on the UI thread. The wallpaper bitmap
 * is never read (P-7).
 */
class AndroidWallpaperColorsSource(context: Context) : WallpaperColorsSource {
    private val appContext = context.applicationContext
    private val started = AtomicBoolean(false)
    private val state = MutableStateFlow<WallpaperColorsModel?>(null)
    private val loadedState = MutableStateFlow(Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1)

    override val colors: StateFlow<WallpaperColorsModel?> = state
    override val loaded: StateFlow<Boolean> = loadedState

    override fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1 || !started.compareAndSet(false, true)) return
        val thread = HandlerThread("strata-wallpaper").apply { start() }
        val handler = Handler(thread.looper)
        handler.post {
            val manager = runCatching { WallpaperManager.getInstance(appContext) }.getOrNull()
            if (manager == null) {
                loadedState.value = true
                return@post
            }
            runCatching {
                manager.addOnColorsChangedListener(
                    { colors, which -> if (which and WallpaperManager.FLAG_SYSTEM != 0) publish(colors) },
                    handler,
                )
            }
            publish(runCatching { manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull())
            loadedState.value = true
        }
    }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private fun publish(colors: WallpaperColors?) {
        state.value = colors?.let(::toModel)
    }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private fun toModel(c: WallpaperColors): WallpaperColorsModel =
        WallpaperColorsModel(
            primary = c.primaryColor.toArgb() or OPAQUE,
            secondary = c.secondaryColor?.toArgb()?.or(OPAQUE),
            tertiary = c.tertiaryColor?.toArgb()?.or(OPAQUE),
            supportsDarkText =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (c.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0,
        )
}

/**
 * The platform dynamic scheme (API 31+) from the framework colour resources, mapped to the roles of
 * THEME_SYSTEM §5.2. API 34+ has role resources (`system_surface_container_high_light` …). API 31–33 has only
 * the tonal palettes in fixed tone steps, so the nearest tone is used (docs/impl-notes/M4.md part 2).
 */
class AndroidDynamicColorsSource(context: Context) : DynamicColorsSource {
    private val appContext = context.applicationContext

    override fun read(): DynamicColorsModel? =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> runCatching { roles34() }.getOrNull()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> runCatching { tones31() }.getOrNull()
            else -> null
        }

    private fun c(
        @ColorRes id: Int,
    ): Int = appContext.getColor(id) or OPAQUE

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun roles34(): DynamicColorsModel =
        DynamicColorsModel(
            light =
                DynamicRoles(
                    surface = c(android.R.color.system_surface_light),
                    surfaceContainerHigh = c(android.R.color.system_surface_container_high_light),
                    surfaceContainerHighest = c(android.R.color.system_surface_container_highest_light),
                    onSurface = c(android.R.color.system_on_surface_light),
                    onSurfaceVariant = c(android.R.color.system_on_surface_variant_light),
                    primary = c(android.R.color.system_primary_light),
                    onPrimary = c(android.R.color.system_on_primary_light),
                    outlineVariant = c(android.R.color.system_outline_variant_light),
                ),
            dark =
                DynamicRoles(
                    surface = c(android.R.color.system_surface_dark),
                    surfaceContainerHigh = c(android.R.color.system_surface_container_high_dark),
                    surfaceContainerHighest = c(android.R.color.system_surface_container_highest_dark),
                    onSurface = c(android.R.color.system_on_surface_dark),
                    onSurfaceVariant = c(android.R.color.system_on_surface_variant_dark),
                    primary = c(android.R.color.system_primary_dark),
                    onPrimary = c(android.R.color.system_on_primary_dark),
                    outlineVariant = c(android.R.color.system_outline_variant_dark),
                ),
        )

    /** Tone suffixes: `_10` = tone 99, `_50` = 95, `_100` = 90, `_200` = 80, … `_900` = 10, `_0` = 100. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun tones31(): DynamicColorsModel =
        DynamicColorsModel(
            light =
                DynamicRoles(
                    surface = c(android.R.color.system_neutral1_10),
                    surfaceContainerHigh = c(android.R.color.system_neutral1_50),
                    surfaceContainerHighest = c(android.R.color.system_neutral1_100),
                    onSurface = c(android.R.color.system_neutral1_900),
                    onSurfaceVariant = c(android.R.color.system_neutral2_700),
                    primary = c(android.R.color.system_accent1_600),
                    onPrimary = c(android.R.color.system_accent1_0),
                    outlineVariant = c(android.R.color.system_neutral2_200),
                ),
            dark =
                DynamicRoles(
                    surface = c(android.R.color.system_neutral1_900),
                    surfaceContainerHigh = c(android.R.color.system_neutral1_800),
                    surfaceContainerHighest = c(android.R.color.system_neutral1_700),
                    onSurface = c(android.R.color.system_neutral1_100),
                    onSurfaceVariant = c(android.R.color.system_neutral2_200),
                    primary = c(android.R.color.system_accent1_200),
                    onPrimary = c(android.R.color.system_accent1_800),
                    outlineVariant = c(android.R.color.system_neutral2_700),
                ),
        )
}

/** [AnimatorScaleSource] over `Settings.Global` and `ValueAnimator` (THEME_SYSTEM §4.11). */
class AndroidAnimatorScaleSource(context: Context) : AnimatorScaleSource {
    private val resolver = context.applicationContext.contentResolver

    override fun read(): AnimatorScale {
        val scale =
            runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
                .getOrDefault(1f)
        return AnimatorScale(durationScale = scale, enabled = ValueAnimator.areAnimatorsEnabled())
    }
}

private const val OPAQUE = 0xFF000000.toInt()
