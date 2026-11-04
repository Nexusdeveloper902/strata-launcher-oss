package app.strata.launcher.core.model

/**
 * Per-app icon override (FR-APPS-06): a drawable of an installed icon pack, chosen in the icon chooser. Global, so
 * it survives theme switches (FR-THEME-31). [drawable] is a resource entry name of [packPackage].
 */
data class IconOverride(val packPackage: String, val drawable: String)

/** Shape mask of THEME_SYSTEM §4.6 `icons.shape` (`SYSTEM` = the device mask). */
enum class IconShapeKind { SYSTEM, CIRCLE, SQUIRCLE, ROUNDED_SQUARE, SQUARE }

/** THEME_SYSTEM §4.6 `icons.tint`. */
enum class IconTintMode { NONE, MONOCHROME, ACCENT }

/**
 * The theme half of an [IconRequest] (ARCHITECTURE §9: shape, legacy plate, tint mode and colour, pack). Colours are
 * `0xAARRGGBB` of the resolved theme variant: [plateColor] = `surface`, [tintColor] = `textPrimary` or `accent`.
 */
data class IconStyle(
    val shape: IconShapeKind = IconShapeKind.SYSTEM,
    /** `roundedSquare` corner radius, percent of the icon size (0..50). */
    val cornerRadiusPercent: Int = 25,
    val legacyPlate: Boolean = false,
    val plateColor: Int = 0,
    val tint: IconTintMode = IconTintMode.NONE,
    val tintColor: Int = 0,
    /** Installed pack of the theme, or null for system icons. */
    val packPackage: String? = null,
    /** `pack.fallback: monochrome`: apps the pack does not cover (or every app, without the pack) are tinted. */
    val packFallbackMonochrome: Boolean = false,
) {
    companion object {
        /** Plain system icons: what M3 rendered. */
        val SYSTEM = IconStyle()
    }
}

/**
 * One icon to render (ARCHITECTURE §9). [lastUpdateTime] is part of the cache key, so an app update invalidates it;
 * [style] and [override] are too, so a theme change or a new per-app icon is a new entry.
 */
data class IconRequest(
    val key: AppKey,
    val lastUpdateTime: Long,
    val sizePx: Int,
    val densityDpi: Int,
    val style: IconStyle = IconStyle.SYSTEM,
    val override: IconOverride? = null,
)

/**
 * Where an icon comes from (THEME_SYSTEM §4.6 precedence, ARCHITECTURE §9.1): [PLAIN] is the app's normal system icon,
 * untinted ("System icon (untinted)" in the M8 coverage tool).
 */
enum class IconSource { OVERRIDE, PACK, GLYPH, PLATFORM_MONOCHROME, DESATURATED, PLAIN }

/** An installed icon pack (ADW convention, ANDROID_PLATFORM §11.1). */
data class IconPackInfo(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    /** False when its `appfilter.xml` broke a limit or could not be read (NFR-COMPAT-04): the pack is ignored. */
    val usable: Boolean,
    /** Mapped components; 0 while not parsed yet or when unusable. */
    val mappedCount: Int = 0,
)

/** The discovered packs. [discovered] is false until the first discovery finished. */
data class IconPacks(
    val packs: List<IconPackInfo> = emptyList(),
    val discovered: Boolean = false,
) {
    val usable: Set<String> get() = packs.filter { it.usable }.mapTo(LinkedHashSet()) { it.packageName }
    val unusable: Set<String> get() = packs.filterNot { it.usable }.mapTo(LinkedHashSet()) { it.packageName }

    fun find(packageName: String): IconPackInfo? = packs.firstOrNull { it.packageName == packageName }
}

/** The icon choices of one pack for the chooser (S-ICON): drawable entry names, plus the pack's own mapping for the app. */
data class PackDrawables(
    val packPackage: String,
    val drawables: List<String>,
    /** The drawable the pack maps to the app being edited, if any (shown first). */
    val suggested: String? = null,
)

/**
 * A rendered icon. [native] is the platform bitmap (an `android.graphics.Bitmap`); only `:core:ui` unwraps it,
 * the same escape hatch as the widget view handle (ARCHITECTURE §3). Pure modules never look inside.
 */
class IconImage(
    val native: Any,
    val width: Int,
    val height: Int,
)

/** Result of an icon request: a bitmap, or the monogram placeholder when the icon cannot be loaded. */
sealed interface IconResult {
    data class Image(val image: IconImage) : IconResult

    data object Monogram : IconResult
}
