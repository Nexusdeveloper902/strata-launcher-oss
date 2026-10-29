package app.strata.launcher.core.theme.write

import app.strata.launcher.core.json.JsonNumber
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonValue
import app.strata.launcher.core.json.JsonWriter
import app.strata.launcher.core.theme.model.ThemeSchema
import app.strata.launcher.core.theme.parse.ThemeParser

/**
 * Theme writer format (THEME_SYSTEM §3, §8.3): UTF-8, 2-space indent, LF, trailing newline, known keys
 * in schema order, then unknown members (including `x-*`) in their original order. Values are written
 * as stored, so untouched fields and every unknown subtree round-trip unchanged.
 */
object ThemeWriter {
    private class Shape(val keys: Set<String>, val children: Map<String, Shape> = emptyMap())

    private val FONT = Shape(ThemeParser.FONT_REF)
    private val PALETTE = Shape(ThemeParser.PALETTE)
    private val ROOT =
        Shape(
            ThemeParser.TOP,
            mapOf(
                "colors" to
                    Shape(
                        ThemeParser.COLORS,
                        mapOf("source" to Shape(ThemeParser.SOURCE), "light" to PALETTE, "dark" to PALETTE),
                    ),
                "wallpaper" to Shape(ThemeParser.WALLPAPER, mapOf("image" to Shape(ThemeParser.IMAGE))),
                "typography" to Shape(ThemeParser.TYPOGRAPHY, mapOf("family" to FONT, "clockFamily" to FONT)),
                "list" to Shape(ThemeParser.LIST),
                "icons" to Shape(ThemeParser.ICONS, mapOf("pack" to Shape(ThemeParser.PACK))),
                "clock" to Shape(ThemeParser.CLOCK, mapOf("date" to Shape(ThemeParser.DATE))),
                "favorites" to Shape(ThemeParser.FAVORITES),
                "search" to Shape(ThemeParser.SEARCH),
                "alphabetIndex" to Shape(ThemeParser.ALPHABET_INDEX),
                "animation" to Shape(ThemeParser.ANIMATION),
                "haptics" to Shape(ThemeParser.HAPTICS),
            ),
        )

    fun write(tree: JsonObject): ByteArray = JsonWriter.prettyBytes(canonical(tree))

    fun writeString(tree: JsonObject): String = JsonWriter.pretty(canonical(tree))

    /** The tree in writer order, with `schemaMinor = max(file, app)` (§10.5). */
    fun canonical(tree: JsonObject): JsonObject {
        var t = tree
        val minor = t["schemaMinor"]
        if (minor is JsonNumber && minor.isIntegral &&
            minor.toBigDecimal() < java.math.BigDecimal.valueOf(ThemeSchema.MINOR.toLong())
        ) {
            t = t.with("schemaMinor", JsonNumber.of(ThemeSchema.MINOR))
        }
        return order(t, ROOT)
    }

    private fun order(
        obj: JsonObject,
        shape: Shape,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonValue>()
        for (k in shape.keys) {
            val v = obj[k] ?: continue
            val child = shape.children[k]
            out[k] = if (child != null && v is JsonObject) order(v, child) else v
        }
        for (k in obj.keys) if (k !in shape.keys) out[k] = obj[k]!!
        return JsonObject(out)
    }
}
