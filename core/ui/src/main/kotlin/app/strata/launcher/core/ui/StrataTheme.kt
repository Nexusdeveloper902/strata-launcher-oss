package app.strata.launcher.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.model.LabelCase
import app.strata.launcher.core.theme.model.Variant
import app.strata.launcher.core.theme.resolve.ResolvedAnimation
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import app.strata.launcher.core.theme.store.ThemePair
import java.util.Locale
import kotlin.math.roundToInt

/** Palette roles of THEME_SYSTEM §4.2 as Compose colours (VISUAL_SPEC §6). */
@Immutable
data class StrataColors(
    val background: Color,
    val surface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val onAccent: Color,
    val divider: Color,
    val highlight: Color,
    val scrim: Color,
)

/** Type tokens of VISUAL_SPEC §3. Sizes are in sp, so the system font scale applies on top of the theme scale. */
@Immutable
data class StrataTypography(
    val label: TextStyle,
    val section: TextStyle,
    val caption: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val index: TextStyle,
    val bubble: TextStyle,
)

private val LocalResolvedTheme = staticCompositionLocalOf { StrataDefault.resolved(systemDark = false) }
internal val LocalStrataColors = staticCompositionLocalOf { colorsOf(StrataDefault.resolved(systemDark = false)) }
private val LocalStrataTypography =
    staticCompositionLocalOf { typographyOf(StrataDefault.resolved(systemDark = false)) }

/** Accessors for the current theme tokens. */
object Strata {
    val theme: ResolvedTheme
        @Composable get() = LocalResolvedTheme.current
    val colors: StrataColors
        @Composable get() = LocalStrataColors.current
    val typography: StrataTypography
        @Composable get() = LocalStrataTypography.current
}

/**
 * `ResolvedTheme` → CompositionLocals (ARCHITECTURE §2 `:core:ui`). Material components (snackbars,
 * buttons in stubs) get a colour scheme mapped from the same roles, so nothing is hard-coded.
 */
@Composable
fun StrataTheme(
    theme: ResolvedTheme,
    content: @Composable () -> Unit,
) {
    ProvideStrataTheme(theme = theme, colors = remember(theme.colors) { colorsOf(theme) }, content = content)
}

@Composable
private fun ProvideStrataTheme(
    theme: ResolvedTheme,
    colors: StrataColors,
    content: @Composable () -> Unit,
) {
    val typography = remember(theme.typography, theme.alphabetIndex) { typographyOf(theme) }
    val scheme = remember(colors, theme.variant) { schemeOf(colors, dark = theme.variant != Variant.LIGHT) }
    CompositionLocalProvider(
        LocalResolvedTheme provides theme,
        LocalStrataColors provides colors,
        LocalStrataTypography provides typography,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Strata Default for the system dark setting (previews, tests and screens outside the themed app). */
@Composable
fun StrataDefaultTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    StrataTheme(theme = StrataDefault.resolved(systemDark = dark), content = content)
}

/**
 * The active theme from the theme repository: picks the variant for the current system dark setting, so a
 * dark-mode flip needs no new resolution (THEME_SYSTEM §5.1). Content colours switch at once; the backdrop
 * cross-fades over [SWITCH_FADE_MS] × the animation scale when [crossFade] is true (§12; snaps with animations off).
 * Callers pass `crossFade = false` until the first loaded theme was shown, so a cold start never fades from Default.
 */
@Composable
fun StrataTheme(
    themes: ThemePair,
    crossFade: Boolean = true,
    content: @Composable () -> Unit,
) {
    val theme = themes.forSystemDark(isSystemInDarkTheme())
    val colors = remember(theme.colors) { colorsOf(theme) }
    val fade = rememberBackdropFade(Backdrop.of(theme, colors), crossFade, theme.animation.durationMs(SWITCH_FADE_MS))
    CompositionLocalProvider(LocalBackdropFade provides fade) {
        ProvideStrataTheme(theme = theme, colors = colors, content = content)
    }
}

/** The running backdrop fade, when the theme comes from [StrataTheme] with a [ThemePair]. */
private val LocalBackdropFade = staticCompositionLocalOf<BackdropFade?> { null }

/** The home and list backdrop for the current theme, cross-fading on theme switches (read in draw). */
val Strata.backdropFade: BackdropFade?
    @Composable get() = LocalBackdropFade.current

/**
 * The home and list backdrop, `E(W)` of THEME_SYSTEM §7.2 drawn over the system wallpaper (VISUAL_SPEC §6):
 * `scrim` at `dim`, then `background` at `backgroundOpacity`; with `wallpaper.show` off, `background` opaque.
 * With a [fade], the values are read in the draw phase, so a theme cross-fade only redraws.
 */
fun Modifier.strataBackdrop(
    theme: ResolvedTheme,
    colors: StrataColors,
    fade: BackdropFade? = null,
): Modifier {
    if (fade != null) {
        return drawBehind {
            val b = fade.current
            drawRect(b.scrim.copy(alpha = b.dim))
            drawRect(b.background.copy(alpha = b.opacity))
        }
    }
    return if (theme.wallpaper.show) {
        background(colors.scrim.copy(alpha = theme.wallpaper.dim.toFloat()))
            .background(colors.background.copy(alpha = theme.wallpaper.backgroundOpacity.toFloat()))
    } else {
        background(colors.background)
    }
}

/**
 * A UI animation duration for [baseMs] under the theme (THEME_SYSTEM §4.11): base × theme scale. Compose applies
 * the system animator scale on top (`MotionDurationScale`), so the effective duration is base × theme × system.
 * 0 when animations are off (system scale 0, "Remove animations", or theme scale 0).
 */
fun ResolvedAnimation.durationMs(baseMs: Int): Int = if (!enabled) 0 else (baseMs * themeDurationScale).roundToInt()

/** THEME_SYSTEM `typography.labelCase`. */
fun LabelCase.apply(
    text: String,
    locale: Locale = Locale.getDefault(),
): String =
    when (this) {
        LabelCase.AS_IS -> text
        LabelCase.LOWER -> text.lowercase(locale)
        LabelCase.UPPER -> text.uppercase(locale)
        LabelCase.TITLE -> text.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(locale) } }
    }

internal fun colorsOf(theme: ResolvedTheme): StrataColors =
    with(theme.colors) {
        StrataColors(
            background = Color(background),
            surface = Color(surface),
            textPrimary = Color(textPrimary),
            textSecondary = Color(textSecondary),
            accent = Color(accent),
            onAccent = Color(onAccent),
            divider = Color(divider),
            highlight = Color(highlight),
            scrim = Color(scrim),
        )
    }

internal fun typographyOf(theme: ResolvedTheme): StrataTypography {
    val t = theme.typography
    val scale = t.scale.toFloat()
    val family = FontFamily.Default // bundled and user fonts arrive with the font store (M4/M5)
    val label = FontWeight(t.labelWeight.coerceIn(1, 1000))
    val heading = FontWeight(t.headingWeight.coerceIn(1, 1000))
    val spacing: TextUnit = t.letterSpacingEm.toFloat().em

    fun style(
        size: Float,
        weight: FontWeight,
        themed: Boolean = true,
    ) = TextStyle(
        fontFamily = if (themed) family else FontFamily.Default,
        fontSize = size.sp,
        lineHeight = (size * LINE_HEIGHT).sp,
        fontWeight = weight,
        letterSpacing = if (themed) spacing else 0.em,
    )
    val small = !t.smallTextUsesSystemFont
    return StrataTypography(
        label = style(LABEL_SP * scale, label),
        section = style(SECTION_SP, heading, small).copy(letterSpacing = SECTION_TRACKING.em),
        caption = style(CAPTION_SP, FontWeight.Normal, small),
        title = style(TITLE_SP, heading, themed = false),
        body = style(BODY_SP, FontWeight.Normal, themed = false),
        index = style(theme.alphabetIndex.textSizeSp.toFloat(), FontWeight.Medium, small),
        bubble = style(BUBBLE_SP, FontWeight.SemiBold),
    )
}

private fun schemeOf(
    c: StrataColors,
    dark: Boolean,
): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent,
        onPrimary = c.onAccent,
        secondary = c.accent,
        onSecondary = c.onAccent,
        background = c.background,
        onBackground = c.textPrimary,
        surface = c.surface,
        onSurface = c.textPrimary,
        surfaceVariant = c.highlight,
        onSurfaceVariant = c.textSecondary,
        inverseSurface = c.textPrimary,
        inverseOnSurface = c.surface,
        inversePrimary = c.accent,
        secondaryContainer = c.surface,
        onSecondaryContainer = c.textPrimary,
        outline = c.divider,
        outlineVariant = c.divider,
        scrim = c.scrim,
    )
}

/** Theme switch cross-fade (THEME_SYSTEM §12 step 1), before the animation scale. */
const val SWITCH_FADE_MS = 300

private const val LINE_HEIGHT = 1.3f
private const val LABEL_SP = 16f
private const val SECTION_SP = 13f
private const val SECTION_TRACKING = 0.06f
private const val CAPTION_SP = 12f
private const val TITLE_SP = 22f
private const val BODY_SP = 14f
private const val BUBBLE_SP = 40f
