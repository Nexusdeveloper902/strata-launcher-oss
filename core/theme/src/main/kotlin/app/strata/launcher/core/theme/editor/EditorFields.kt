package app.strata.launcher.core.theme.editor

import app.strata.launcher.core.json.JsonBool
import app.strata.launcher.core.json.JsonNumber
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.json.JsonValue
import app.strata.launcher.core.theme.color.Rgb
import app.strata.launcher.core.theme.model.Alignment
import app.strata.launcher.core.theme.model.ClockStyle
import app.strata.launcher.core.theme.model.ColorMode
import app.strata.launcher.core.theme.model.ColorRole
import app.strata.launcher.core.theme.model.Density
import app.strata.launcher.core.theme.model.HapticsMode
import app.strata.launcher.core.theme.model.HourFormat
import app.strata.launcher.core.theme.model.IconShape
import app.strata.launcher.core.theme.model.IconTint
import app.strata.launcher.core.theme.model.IndexGlyphs
import app.strata.launcher.core.theme.model.IndexSide
import app.strata.launcher.core.theme.model.IndexVisibility
import app.strata.launcher.core.theme.model.LabelCase
import app.strata.launcher.core.theme.model.ListEntrance
import app.strata.launcher.core.theme.model.SearchPosition
import app.strata.launcher.core.theme.model.SearchStyle
import app.strata.launcher.core.theme.model.SourceKind
import app.strata.launcher.core.theme.model.ThemeDocument
import app.strata.launcher.core.theme.model.Variant
import app.strata.launcher.core.theme.model.VerticalPosition
import app.strata.launcher.core.theme.model.WireEnum
import app.strata.launcher.core.theme.parse.ThemeParser
import app.strata.launcher.core.theme.write.ThemeEncoder
import java.math.BigDecimal
import java.math.RoundingMode

/** How an editor control edits a field (THEME_SYSTEM §4 types and ranges). */
sealed interface FieldKind {
    data object Toggle : FieldKind

    /** An enum; [options] are the wire values in schema order. */
    data class Choice(val options: List<String>) : FieldKind

    /** An integer slider with keyboard/D-pad steps of [step]. */
    data class Whole(val min: Int, val max: Int, val step: Int = 1) : FieldKind

    /** A number slider; values are stored rounded to [decimals]. */
    data class Decimal(val min: Double, val max: Double, val step: Double, val decimals: Int = 2) : FieldKind

    /** `#RRGGBB` (hex field, HCT sliders, swatches, contrast chip). */
    data object Colour : FieldKind

    /** Free text up to [maxLength] UTF-16 units; [pattern] when the schema has one. */
    data class Text(val maxLength: Int, val pattern: Regex? = null, val multiline: Boolean = false) : FieldKind
}

/** One editable field. [role]/[variant] are set for palette colours (contrast chips, §7.6). */
data class EditorField(
    val path: FieldPath,
    val kind: FieldKind,
    val role: ColorRole? = null,
    val variant: Variant? = null,
    /** False for fields that cannot be reset on their own (the required `name`). */
    val resettable: Boolean = true,
) {
    val id: String get() = path.toString()
}

/** A §4 group as shown in the editor (`about` holds the §4.1 metadata). */
data class EditorGroup(val key: String, val fields: List<EditorField>) {
    /** The tree member reset by "Reset group"; null for the top-level metadata. */
    val treeKey: String? get() = if (key == ABOUT) null else key

    companion object {
        const val ABOUT = "about"
    }
}

/**
 * The editor's field catalogue, grouped as in THEME_SYSTEM §4. Fonts, icon packs, locked roles and the wallpaper
 * image are edited elsewhere (font manager, icon chooser, create-from-image) or through the JSON view.
 */
object EditorFields {
    private inline fun <reified E> choice(): FieldKind.Choice where E : Enum<E>, E : WireEnum =
        FieldKind.Choice(enumValues<E>().map { it.wire })

    private fun f(
        kind: FieldKind,
        vararg keys: String,
    ) = EditorField(FieldPath(*keys), kind)

    private val toggle = FieldKind.Toggle
    private val alignment = choice<Alignment>()

    private fun palette(v: Variant): List<EditorField> =
        ColorRole.entries.map {
            EditorField(FieldPath("colors", v.wire, it.wire), FieldKind.Colour, role = it, variant = v)
        }

    val GROUPS: List<EditorGroup> =
        listOf(
            EditorGroup(
                EditorGroup.ABOUT,
                listOf(
                    EditorField(FieldPath("name"), FieldKind.Text(NAME_MAX), resettable = false),
                    f(FieldKind.Text(NAME_MAX), "author"),
                    f(FieldKind.Text(DESCRIPTION_MAX, multiline = true), "description"),
                ),
            ),
            EditorGroup(
                "colors",
                listOf(
                    f(choice<ColorMode>(), "colors", "mode"),
                    f(choice<SourceKind>(), "colors", "source", "kind"),
                    f(FieldKind.Colour, "colors", "source", "seedColor"),
                    f(toggle, "colors", "pureBlack"),
                ) + palette(Variant.LIGHT) + palette(Variant.DARK),
            ),
            EditorGroup(
                "wallpaper",
                listOf(
                    f(toggle, "wallpaper", "show"),
                    f(FieldKind.Decimal(0.0, 0.95, 0.05), "wallpaper", "dim"),
                    f(FieldKind.Decimal(0.0, 1.0, 0.05), "wallpaper", "backgroundOpacity"),
                ),
            ),
            EditorGroup(
                "typography",
                listOf(
                    f(FieldKind.Decimal(0.8, 1.5, 0.05), "typography", "scale"),
                    f(FieldKind.Whole(100, 900, 100), "typography", "labelWeight"),
                    f(FieldKind.Whole(100, 900, 100), "typography", "headingWeight"),
                    f(FieldKind.Whole(100, 900, 100), "typography", "clockWeight"),
                    f(FieldKind.Decimal(-0.05, 0.2, 0.01), "typography", "letterSpacingEm"),
                    f(choice<LabelCase>(), "typography", "labelCase"),
                    f(toggle, "typography", "smallTextUsesSystemFont"),
                ),
            ),
            EditorGroup(
                "list",
                listOf(
                    f(choice<Density>(), "list", "density"),
                    f(FieldKind.Whole(48, 96), "list", "rowHeightDp"),
                    f(FieldKind.Whole(0, 48), "list", "horizontalPaddingDp"),
                    f(FieldKind.Whole(0, 24), "list", "itemSpacingDp"),
                    f(toggle, "list", "sectionHeaders"),
                    f(toggle, "list", "showDividers"),
                ),
            ),
            EditorGroup(
                "icons",
                listOf(
                    f(toggle, "icons", "visible"),
                    f(FieldKind.Whole(20, 64), "icons", "sizeDp"),
                    f(choice<IconShape>(), "icons", "shape"),
                    f(FieldKind.Whole(0, 50), "icons", "cornerRadiusPercent"),
                    f(choice<IconTint>(), "icons", "tint"),
                    f(toggle, "icons", "legacyIconPlate"),
                    f(toggle, "icons", "labelsVisible"),
                ),
            ),
            EditorGroup(
                "clock",
                listOf(
                    f(toggle, "clock", "visible"),
                    f(choice<ClockStyle>(), "clock", "style"),
                    f(choice<HourFormat>(), "clock", "hourFormat"),
                    f(toggle, "clock", "showSeconds"),
                    f(FieldKind.Whole(24, 128), "clock", "sizeSp"),
                    f(alignment, "clock", "alignment"),
                    f(toggle, "clock", "date", "visible"),
                    f(FieldKind.Text(SKELETON_MAX, ThemeParser.SKELETON), "clock", "date", "skeleton"),
                    f(FieldKind.Whole(12, 32), "clock", "date", "sizeSp"),
                ),
            ),
            EditorGroup(
                "favorites",
                listOf(
                    f(FieldKind.Decimal(0.8, 2.0, 0.05), "favorites", "textScale"),
                    f(alignment, "favorites", "alignment"),
                    f(choice<VerticalPosition>(), "favorites", "verticalPosition"),
                    f(toggle, "favorites", "showIcons"),
                    f(toggle, "favorites", "showLabels"),
                    f(FieldKind.Whole(0, 32), "favorites", "spacingDp"),
                ),
            ),
            EditorGroup(
                "search",
                listOf(
                    f(choice<SearchPosition>(), "search", "position"),
                    f(choice<SearchStyle>(), "search", "style"),
                    f(FieldKind.Whole(0, 28), "search", "cornerRadiusDp"),
                    f(toggle, "search", "showHint"),
                ),
            ),
            EditorGroup(
                "alphabetIndex",
                listOf(
                    f(choice<IndexVisibility>(), "alphabetIndex", "visibility"),
                    f(choice<IndexSide>(), "alphabetIndex", "side"),
                    f(choice<IndexGlyphs>(), "alphabetIndex", "glyphs"),
                    f(FieldKind.Whole(10, 18), "alphabetIndex", "textSizeSp"),
                    f(toggle, "alphabetIndex", "showBubble"),
                ),
            ),
            EditorGroup(
                "animation",
                listOf(
                    f(FieldKind.Decimal(0.0, 2.0, 0.05), "animation", "durationScale"),
                    f(choice<ListEntrance>(), "animation", "listEntrance"),
                ),
            ),
            EditorGroup("haptics", listOf(f(choice<HapticsMode>(), "haptics", "mode"))),
        )

    val ALL: List<EditorField> = GROUPS.flatMap { it.fields }

    private val byId: Map<String, EditorField> = ALL.associateBy { it.id }

    fun field(id: String): EditorField? = byId[id]

    fun group(key: String): EditorGroup? = GROUPS.firstOrNull { it.key == key }

    fun colour(
        variant: Variant,
        role: ColorRole,
    ): EditorField = byId.getValue(FieldPath("colors", variant.wire, role.wire).toString())

    /** Every field's effective value for [doc] (what the preview renders), as JSON at the field's path. */
    fun effective(doc: ThemeDocument): JsonObject = ThemeEncoder.encode(doc, full = true)

    /**
     * The value a control shows: the effective value, or for fields without one (an absent seed, `rowHeightDp`
     * following the density) the value the renderer uses.
     */
    fun displayValue(
        field: EditorField,
        doc: ThemeDocument,
        effective: JsonObject = effective(doc),
    ): JsonValue? {
        TreeEdits.get(effective, field.path)?.let { return it }
        return when (field.id) {
            "list.rowHeightDp" -> JsonNumber.of(doc.list.rowHeightDp ?: doc.list.density.rowHeightDp)
            "colors.source.seedColor" -> JsonString(Rgb.toHex(doc.colors.light.accent))
            "author", "description" -> JsonString("")
            else -> null
        }
    }

    /** The JSON value a control writes, normalised to the field's range and precision (never outside §4). */
    fun encode(
        field: EditorField,
        value: Any,
    ): JsonValue? =
        when (val k = field.kind) {
            FieldKind.Toggle -> {
                (value as? Boolean)?.let { JsonBool.of(it) }
            }

            is FieldKind.Choice -> {
                (value as? String)?.takeIf { it in k.options }?.let { JsonString(it) }
            }

            is FieldKind.Whole -> {
                (value as? Number)?.let {
                    val snapped = Math.round((it.toDouble() - k.min) / k.step) * k.step + k.min
                    JsonNumber.of(snapped.toInt().coerceIn(k.min, k.max))
                }
            }

            is FieldKind.Decimal -> {
                (value as? Number)?.let {
                    val d =
                        BigDecimal(it.toDouble().coerceIn(k.min, k.max).toString())
                            .setScale(k.decimals, RoundingMode.HALF_EVEN)
                            .toDouble()
                    JsonNumber.of(d.coerceIn(k.min, k.max))
                }
            }

            FieldKind.Colour -> {
                when (value) {
                    is Int -> JsonString(Rgb.toHex(value))
                    is String -> Rgb.parseHex(value.trim())?.let { JsonString(Rgb.toHex(it)) }
                    else -> null
                }
            }

            is FieldKind.Text -> {
                (value as? String)
                    ?.take(k.maxLength)
                    ?.takeIf { k.pattern == null || k.pattern.matches(it) }
                    ?.let { JsonString(it) }
            }
        }

    const val NAME_MAX = 64
    const val DESCRIPTION_MAX = 500
    private const val SKELETON_MAX = 16
}
