package app.strata.launcher.core.theme.model

/**
 * Typed view of a theme file after validation (THEME_SYSTEM §4, §8.1 stage 5). Every field holds the
 * value used for rendering, i.e. after the field's **On invalid** policy. The raw tree (with unknown
 * members and preserved originals) lives next to it in [app.strata.launcher.core.theme.parse.ParsedTheme].
 *
 * The defaults of every constructor parameter are the schema defaults, so `ThemeDocument(id, name)` is
 * Strata Default with another identity.
 */
data class ThemeDocument(
    val id: String,
    val name: String,
    val schemaVersion: Int = ThemeSchema.MAJOR,
    val schemaMinor: Int = 0,
    val author: String? = null,
    val description: String? = null,
    val createdAt: String? = null,
    val modifiedAt: String? = null,
    val basedOn: String? = null,
    val colors: ColorsSpec = ColorsSpec(),
    val wallpaper: WallpaperSpec = WallpaperSpec(),
    val typography: TypographySpec = TypographySpec(),
    val list: ListSpec = ListSpec(),
    val icons: IconsSpec = IconsSpec(),
    val clock: ClockSpec = ClockSpec(),
    val favorites: FavoritesSpec = FavoritesSpec(),
    val search: SearchSpec = SearchSpec(),
    val alphabetIndex: AlphabetIndexSpec = AlphabetIndexSpec(),
    val animation: AnimationSpec = AnimationSpec(),
    val haptics: HapticsSpec = HapticsSpec(),
)

object ThemeSchema {
    /** App constants of THEME_SYSTEM §10.5. */
    const val MAJOR = 1
    const val MINOR = 0
}

/** A schema enum: [wire] is the exact JSON string. Mapping goes through fixed tables only (§9). */
interface WireEnum {
    val wire: String
}

inline fun <reified E> wireValueOf(s: String): E? where E : Enum<E>, E : WireEnum =
    enumValues<E>().firstOrNull {
        it.wire ==
            s
    }

enum class ColorRole(override val wire: String) : WireEnum {
    BACKGROUND("background"),
    SURFACE("surface"),
    TEXT_PRIMARY("textPrimary"),
    TEXT_SECONDARY("textSecondary"),
    ACCENT("accent"),
    ON_ACCENT("onAccent"),
    DIVIDER("divider"),
    HIGHLIGHT("highlight"),
    SCRIM("scrim"),
}

enum class Variant(override val wire: String) : WireEnum { LIGHT("light"), DARK("dark") }

/** Nine opaque colours, `0xFFRRGGBB`. */
data class Palette(
    val background: Int,
    val surface: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int,
    val onAccent: Int,
    val divider: Int,
    val highlight: Int,
    val scrim: Int,
) {
    operator fun get(role: ColorRole): Int =
        when (role) {
            ColorRole.BACKGROUND -> background
            ColorRole.SURFACE -> surface
            ColorRole.TEXT_PRIMARY -> textPrimary
            ColorRole.TEXT_SECONDARY -> textSecondary
            ColorRole.ACCENT -> accent
            ColorRole.ON_ACCENT -> onAccent
            ColorRole.DIVIDER -> divider
            ColorRole.HIGHLIGHT -> highlight
            ColorRole.SCRIM -> scrim
        }

    fun with(
        role: ColorRole,
        argb: Int,
    ): Palette =
        when (role) {
            ColorRole.BACKGROUND -> copy(background = argb)
            ColorRole.SURFACE -> copy(surface = argb)
            ColorRole.TEXT_PRIMARY -> copy(textPrimary = argb)
            ColorRole.TEXT_SECONDARY -> copy(textSecondary = argb)
            ColorRole.ACCENT -> copy(accent = argb)
            ColorRole.ON_ACCENT -> copy(onAccent = argb)
            ColorRole.DIVIDER -> copy(divider = argb)
            ColorRole.HIGHLIGHT -> copy(highlight = argb)
            ColorRole.SCRIM -> copy(scrim = argb)
        }

    companion object {
        fun of(roles: Map<ColorRole, Int>): Palette {
            require(roles.keys.containsAll(ColorRole.entries)) { "all nine roles required" }
            return Palette(
                roles.getValue(ColorRole.BACKGROUND),
                roles.getValue(ColorRole.SURFACE),
                roles.getValue(ColorRole.TEXT_PRIMARY),
                roles.getValue(ColorRole.TEXT_SECONDARY),
                roles.getValue(ColorRole.ACCENT),
                roles.getValue(ColorRole.ON_ACCENT),
                roles.getValue(ColorRole.DIVIDER),
                roles.getValue(ColorRole.HIGHLIGHT),
                roles.getValue(ColorRole.SCRIM),
            )
        }
    }
}

/** Built-in palettes of THEME_SYSTEM §4.2 (also Strata Default's). */
object BuiltInPalettes {
    val LIGHT =
        Palette(
            background = 0xFFFAFAF7.toInt(),
            surface = 0xFFEDECE6.toInt(),
            textPrimary = 0xFF1B1B1A.toInt(),
            textSecondary = 0xFF57564F.toInt(),
            accent = 0xFF2F5D8A.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            divider = 0xFFD6D4CC.toInt(),
            highlight = 0xFFE3E1D9.toInt(),
            scrim = 0xFFFFFFFF.toInt(),
        )
    val DARK =
        Palette(
            background = 0xFF121212.toInt(),
            surface = 0xFF1F1F1F.toInt(),
            textPrimary = 0xFFECECEA.toInt(),
            textSecondary = 0xFFB0B0AA.toInt(),
            accent = 0xFF8DB4DE.toInt(),
            onAccent = 0xFF0B2239.toInt(),
            divider = 0xFF33332F.toInt(),
            highlight = 0xFF2B2B28.toInt(),
            scrim = 0xFF000000.toInt(),
        )

    fun of(variant: Variant): Palette = if (variant == Variant.LIGHT) LIGHT else DARK
}

enum class ColorMode(override val wire: String) : WireEnum {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    WALLPAPER("wallpaper"),
}

enum class SourceKind(override val wire: String) : WireEnum {
    FIXED("fixed"),
    SEED("seed"),
    WALLPAPER("wallpaper"),
    DYNAMIC("dynamic"),
}

data class ColorsSpec(
    val mode: ColorMode = ColorMode.SYSTEM,
    val sourceKind: SourceKind = SourceKind.FIXED,
    /** Valid `#RRGGBB` seed, or null. A `seed` kind without one behaves as `fixed`. */
    val seedColor: Int? = null,
    val lockedRoles: Set<ColorRole> = emptySet(),
    val pureBlack: Boolean = false,
    val light: Palette = BuiltInPalettes.LIGHT,
    val dark: Palette = BuiltInPalettes.DARK,
) {
    fun palette(v: Variant): Palette = if (v == Variant.LIGHT) light else dark
}

enum class ImageMediaType(override val wire: String) : WireEnum {
    PNG("image/png"),
    JPEG("image/jpeg"),
    WEBP("image/webp"),
}

data class WallpaperImage(val sha256: String, val mediaType: ImageMediaType? = null)

data class WallpaperSpec(
    val show: Boolean = false,
    val dim: Double = 0.5,
    val backgroundOpacity: Double = 0.0,
    val image: WallpaperImage? = null,
)

sealed interface FontRef {
    data object System : FontRef

    data class Bundled(val id: String) : FontRef

    data class User(val sha256: String, val displayName: String? = null) : FontRef
}

enum class LabelCase(override val wire: String) : WireEnum {
    AS_IS("asIs"),
    LOWER("lower"),
    UPPER("upper"),
    TITLE("title"),
}

data class TypographySpec(
    val family: FontRef = FontRef.System,
    /** null → same as [family]. */
    val clockFamily: FontRef? = null,
    val scale: Double = 1.0,
    val labelWeight: Int = 400,
    val headingWeight: Int = 500,
    val clockWeight: Int = 300,
    val letterSpacingEm: Double = 0.0,
    val labelCase: LabelCase = LabelCase.AS_IS,
    val smallTextUsesSystemFont: Boolean = false,
)

enum class Density(override val wire: String, val rowHeightDp: Int) : WireEnum {
    COMPACT("compact", 48),
    COMFORTABLE("comfortable", 56),
    SPACIOUS("spacious", 64),
}

data class ListSpec(
    val density: Density = Density.COMFORTABLE,
    /** null → [Density.rowHeightDp]. */
    val rowHeightDp: Int? = null,
    val horizontalPaddingDp: Int = 16,
    val itemSpacingDp: Int = 0,
    val sectionHeaders: Boolean = true,
    val showDividers: Boolean = false,
)

enum class IconShape(override val wire: String) : WireEnum {
    SYSTEM("system"),
    CIRCLE("circle"),
    SQUIRCLE("squircle"),
    ROUNDED_SQUARE("roundedSquare"),
    SQUARE("square"),
}

enum class PackFallback(override val wire: String) : WireEnum { SYSTEM("system"), MONOCHROME("monochrome") }

data class IconPackRef(
    val packageName: String,
    val displayName: String? = null,
    val fallback: PackFallback = PackFallback.SYSTEM,
)

enum class IconTint(override val wire: String) : WireEnum { NONE("none"), MONOCHROME("monochrome"), ACCENT("accent") }

data class IconsSpec(
    val visible: Boolean = true,
    val sizeDp: Int = 32,
    val shape: IconShape = IconShape.SYSTEM,
    val cornerRadiusPercent: Int = 25,
    val pack: IconPackRef? = null,
    val tint: IconTint = IconTint.NONE,
    val legacyIconPlate: Boolean = true,
    val labelsVisible: Boolean = true,
)

enum class ClockStyle(override val wire: String) : WireEnum {
    NUMERIC("numeric"),
    NUMERIC_STACKED("numericStacked"),
    WORDS("words"),
    ANALOG_MINIMAL("analogMinimal"),
}

enum class HourFormat(override val wire: String) : WireEnum { SYSTEM("system"), H12("h12"), H24("h24") }

enum class Alignment(override val wire: String) : WireEnum { START("start"), CENTER("center"), END("end") }

data class DateSpec(
    val visible: Boolean = true,
    val skeleton: String = "EEEEdMMMM",
    val sizeSp: Int = 16,
)

data class ClockSpec(
    val visible: Boolean = true,
    val style: ClockStyle = ClockStyle.NUMERIC,
    val hourFormat: HourFormat = HourFormat.SYSTEM,
    val showSeconds: Boolean = false,
    val sizeSp: Int = 56,
    val alignment: Alignment = Alignment.START,
    val date: DateSpec = DateSpec(),
)

enum class VerticalPosition(override val wire: String) : WireEnum { TOP("top"), CENTER("center"), BOTTOM("bottom") }

data class FavoritesSpec(
    val textScale: Double = 1.3,
    val alignment: Alignment = Alignment.START,
    val verticalPosition: VerticalPosition = VerticalPosition.BOTTOM,
    val showIcons: Boolean = false,
    val showLabels: Boolean = true,
    val spacingDp: Int = 8,
)

enum class SearchPosition(override val wire: String) : WireEnum { TOP("top"), BOTTOM("bottom"), HIDDEN("hidden") }

enum class SearchStyle(override val wire: String) : WireEnum { PILL("pill"), BOX("box"), UNDERLINE("underline") }

data class SearchSpec(
    val position: SearchPosition = SearchPosition.BOTTOM,
    val style: SearchStyle = SearchStyle.PILL,
    val cornerRadiusDp: Int = 24,
    val showHint: Boolean = true,
)

enum class IndexVisibility(override val wire: String) : WireEnum {
    ALWAYS(
        "always",
    ),
    LIST_ONLY("listOnly"),
    NEVER("never"),
}

enum class IndexSide(override val wire: String) : WireEnum { END("end"), START("start") }

enum class IndexGlyphs(override val wire: String) : WireEnum { LETTERS("letters"), DOTS("dots") }

data class AlphabetIndexSpec(
    val visibility: IndexVisibility = IndexVisibility.ALWAYS,
    val side: IndexSide = IndexSide.END,
    val glyphs: IndexGlyphs = IndexGlyphs.LETTERS,
    val textSizeSp: Int = 12,
    val showBubble: Boolean = true,
)

enum class ListEntrance(override val wire: String) : WireEnum { NONE("none"), FADE("fade"), SLIDE("slide") }

data class AnimationSpec(
    val durationScale: Double = 1.0,
    val listEntrance: ListEntrance = ListEntrance.FADE,
)

enum class HapticsMode(override val wire: String) : WireEnum { FOLLOW_SYSTEM("followSystem"), OFF("off") }

data class HapticsSpec(val mode: HapticsMode = HapticsMode.FOLLOW_SYSTEM)
