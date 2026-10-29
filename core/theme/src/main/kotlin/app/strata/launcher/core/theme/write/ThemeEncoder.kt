package app.strata.launcher.core.theme.write

import app.strata.launcher.core.json.JsonArray
import app.strata.launcher.core.json.JsonBool
import app.strata.launcher.core.json.JsonNumber
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.json.JsonValue
import app.strata.launcher.core.theme.color.Rgb
import app.strata.launcher.core.theme.model.BuiltInPalettes
import app.strata.launcher.core.theme.model.ColorRole
import app.strata.launcher.core.theme.model.FontRef
import app.strata.launcher.core.theme.model.Palette
import app.strata.launcher.core.theme.model.ThemeDocument
import app.strata.launcher.core.theme.model.WireEnum

/**
 * [ThemeDocument] → JSON tree, for themes created in code (Strata Default, editor output, generators).
 * Only the required keys and fields that differ from the schema default are emitted ("sparse"), unless
 * [full] is set. Files read from disk are written from their preserved tree instead ([ThemeWriter]).
 */
object ThemeEncoder {
    fun encode(
        doc: ThemeDocument,
        full: Boolean = false,
    ): JsonObject {
        val d = ThemeDocument(doc.id, doc.name)
        val top = Builder(full)
        top.put("schemaVersion", JsonNumber.of(doc.schemaVersion), force = true)
        top.int("schemaMinor", doc.schemaMinor, d.schemaMinor)
        top.put("id", JsonString(doc.id), force = true)
        top.put("name", JsonString(doc.name), force = true)
        doc.author?.let { top.put("author", JsonString(it), force = true) }
        doc.description?.let { top.put("description", JsonString(it), force = true) }
        doc.createdAt?.let { top.put("createdAt", JsonString(it), force = true) }
        doc.modifiedAt?.let { top.put("modifiedAt", JsonString(it), force = true) }
        doc.basedOn?.let { top.put("basedOn", JsonString(it), force = true) }

        top.group("colors") {
            val c = doc.colors
            val dc = d.colors
            enum("mode", c.mode, dc.mode)
            group("source") {
                enum("kind", c.sourceKind, dc.sourceKind)
                c.seedColor?.let { put("seedColor", JsonString(Rgb.toHex(it)), force = true) }
                if (c.lockedRoles.isNotEmpty() || full) put("lockedRoles", JsonArray(c.lockedRoles.map { JsonString(it.wire) }), force = true)
            }
            bool("pureBlack", c.pureBlack, dc.pureBlack)
            group("light") { palette(c.light, BuiltInPalettes.LIGHT) }
            group("dark") { palette(c.dark, BuiltInPalettes.DARK) }
        }
        top.group("wallpaper") {
            val w = doc.wallpaper
            val dw = d.wallpaper
            bool("show", w.show, dw.show)
            num("dim", w.dim, dw.dim)
            num("backgroundOpacity", w.backgroundOpacity, dw.backgroundOpacity)
            w.image?.let { img ->
                put(
                    "image",
                    JsonObject(
                        LinkedHashMap<String, JsonValue>().apply {
                            put("sha256", JsonString(img.sha256))
                            img.mediaType?.let { put("mediaType", JsonString(it.wire)) }
                        },
                    ),
                    force = true,
                )
            }
        }
        top.group("typography") {
            val t = doc.typography
            val dt = d.typography
            if (t.family != dt.family || full) put("family", font(t.family), force = true)
            t.clockFamily?.let { put("clockFamily", font(it), force = true) }
            num("scale", t.scale, dt.scale)
            int("labelWeight", t.labelWeight, dt.labelWeight)
            int("headingWeight", t.headingWeight, dt.headingWeight)
            int("clockWeight", t.clockWeight, dt.clockWeight)
            num("letterSpacingEm", t.letterSpacingEm, dt.letterSpacingEm)
            enum("labelCase", t.labelCase, dt.labelCase)
            bool("smallTextUsesSystemFont", t.smallTextUsesSystemFont, dt.smallTextUsesSystemFont)
        }
        top.group("list") {
            val l = doc.list
            val dl = d.list
            enum("density", l.density, dl.density)
            l.rowHeightDp?.let { put("rowHeightDp", JsonNumber.of(it), force = true) }
            int("horizontalPaddingDp", l.horizontalPaddingDp, dl.horizontalPaddingDp)
            int("itemSpacingDp", l.itemSpacingDp, dl.itemSpacingDp)
            bool("sectionHeaders", l.sectionHeaders, dl.sectionHeaders)
            bool("showDividers", l.showDividers, dl.showDividers)
        }
        top.group("icons") {
            val i = doc.icons
            val di = d.icons
            bool("visible", i.visible, di.visible)
            int("sizeDp", i.sizeDp, di.sizeDp)
            enum("shape", i.shape, di.shape)
            int("cornerRadiusPercent", i.cornerRadiusPercent, di.cornerRadiusPercent)
            i.pack?.let { p ->
                put(
                    "pack",
                    JsonObject(
                        LinkedHashMap<String, JsonValue>().apply {
                            put("package", JsonString(p.packageName))
                            p.displayName?.let { put("displayName", JsonString(it)) }
                            put("fallback", JsonString(p.fallback.wire))
                        },
                    ),
                    force = true,
                )
            }
            enum("tint", i.tint, di.tint)
            bool("legacyIconPlate", i.legacyIconPlate, di.legacyIconPlate)
            bool("labelsVisible", i.labelsVisible, di.labelsVisible)
        }
        top.group("clock") {
            val c = doc.clock
            val dc = d.clock
            bool("visible", c.visible, dc.visible)
            enum("style", c.style, dc.style)
            enum("hourFormat", c.hourFormat, dc.hourFormat)
            bool("showSeconds", c.showSeconds, dc.showSeconds)
            int("sizeSp", c.sizeSp, dc.sizeSp)
            enum("alignment", c.alignment, dc.alignment)
            group("date") {
                bool("visible", c.date.visible, dc.date.visible)
                if (c.date.skeleton != dc.date.skeleton || full) put("skeleton", JsonString(c.date.skeleton), force = true)
                int("sizeSp", c.date.sizeSp, dc.date.sizeSp)
            }
        }
        top.group("favorites") {
            val f = doc.favorites
            val df = d.favorites
            num("textScale", f.textScale, df.textScale)
            enum("alignment", f.alignment, df.alignment)
            enum("verticalPosition", f.verticalPosition, df.verticalPosition)
            bool("showIcons", f.showIcons, df.showIcons)
            bool("showLabels", f.showLabels, df.showLabels)
            int("spacingDp", f.spacingDp, df.spacingDp)
        }
        top.group("search") {
            val s = doc.search
            val ds = d.search
            enum("position", s.position, ds.position)
            enum("style", s.style, ds.style)
            int("cornerRadiusDp", s.cornerRadiusDp, ds.cornerRadiusDp)
            bool("showHint", s.showHint, ds.showHint)
        }
        top.group("alphabetIndex") {
            val a = doc.alphabetIndex
            val da = d.alphabetIndex
            enum("visibility", a.visibility, da.visibility)
            enum("side", a.side, da.side)
            enum("glyphs", a.glyphs, da.glyphs)
            int("textSizeSp", a.textSizeSp, da.textSizeSp)
            bool("showBubble", a.showBubble, da.showBubble)
        }
        top.group("animation") {
            num("durationScale", doc.animation.durationScale, d.animation.durationScale)
            enum("listEntrance", doc.animation.listEntrance, d.animation.listEntrance)
        }
        top.group("haptics") { enum("mode", doc.haptics.mode, d.haptics.mode) }
        return ThemeWriter.canonical(top.build())
    }

    private fun font(f: FontRef): JsonObject =
        when (f) {
            FontRef.System -> JsonObject("kind" to JsonString("system"))
            is FontRef.Bundled -> JsonObject("kind" to JsonString("bundled"), "id" to JsonString(f.id))
            is FontRef.User ->
                JsonObject(
                    LinkedHashMap<String, JsonValue>().apply {
                        put("kind", JsonString("user"))
                        put("sha256", JsonString(f.sha256))
                        f.displayName?.let { put("displayName", JsonString(it)) }
                    },
                )
        }

    private class Builder(val full: Boolean) {
        val map = LinkedHashMap<String, JsonValue>()

        fun put(
            key: String,
            v: JsonValue,
            force: Boolean,
        ) {
            if (force || full) map[key] = v
        }

        fun bool(
            key: String,
            v: Boolean,
            d: Boolean,
        ) = put(key, JsonBool.of(v), v != d)

        fun int(
            key: String,
            v: Int,
            d: Int,
        ) = put(key, JsonNumber.of(v), v != d)

        fun num(
            key: String,
            v: Double,
            d: Double,
        ) = put(key, JsonNumber.of(v), v != d)

        fun <E : WireEnum> enum(
            key: String,
            v: E,
            d: E,
        ) = put(key, JsonString(v.wire), v != d)

        fun palette(
            p: Palette,
            base: Palette,
        ) {
            for (r in ColorRole.entries) put(r.wire, JsonString(Rgb.toHex(p[r])), p[r] != base[r])
        }

        fun group(
            key: String,
            block: Builder.() -> Unit,
        ) {
            val b = Builder(full).apply(block)
            if (b.map.isNotEmpty() || full) map[key] = b.build()
        }

        fun build(): JsonObject = JsonObject(map)
    }
}
