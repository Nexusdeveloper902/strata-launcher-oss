package app.strata.launcher.core.theme

import app.strata.launcher.core.json.JsonArray
import app.strata.launcher.core.json.JsonBool
import app.strata.launcher.core.json.JsonNull
import app.strata.launcher.core.json.JsonNumber
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.json.JsonValue
import app.strata.launcher.core.theme.model.Alignment
import app.strata.launcher.core.theme.model.ClockStyle
import app.strata.launcher.core.theme.model.ColorMode
import app.strata.launcher.core.theme.model.ColorRole
import app.strata.launcher.core.theme.model.ColorsSpec
import app.strata.launcher.core.theme.model.Density
import app.strata.launcher.core.theme.model.FontRef
import app.strata.launcher.core.theme.model.IconPackRef
import app.strata.launcher.core.theme.model.IconShape
import app.strata.launcher.core.theme.model.IconTint
import app.strata.launcher.core.theme.model.ImageMediaType
import app.strata.launcher.core.theme.model.LabelCase
import app.strata.launcher.core.theme.model.ListSpec
import app.strata.launcher.core.theme.model.Palette
import app.strata.launcher.core.theme.model.SourceKind
import app.strata.launcher.core.theme.model.ThemeDocument
import app.strata.launcher.core.theme.model.TypographySpec
import app.strata.launcher.core.theme.model.WallpaperImage
import app.strata.launcher.core.theme.model.WallpaperSpec
import app.strata.launcher.core.theme.parse.IssueCode
import app.strata.launcher.core.theme.parse.ThemeParser
import app.strata.launcher.core.theme.write.ThemeEncoder
import app.strata.launcher.core.theme.write.ThemeWriter
import app.strata.launcher.testing.fixtures.ThemeFixtures
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.random.Random

/** T-THEME-21 (unknown keys round trip) and T-THEME-22 (property-based round trip, 5 000 cases). */
class RoundTripTest {
    private fun roundTrip(
        bytes: ByteArray,
    ): Pair<app.strata.launcher.core.theme.parse.ParsedTheme, app.strata.launcher.core.theme.parse.ParsedTheme> {
        val first = parseOk(bytes)
        val second = parseOk(ThemeWriter.write(first.tree))
        return first to second
    }

    /** For every object (sorted by path): its unknown members in order. Their relative order must survive. */
    private fun unknownOrder(
        v: JsonValue,
        path: String = "",
        out: MutableList<String> = ArrayList(),
    ): List<String> {
        when (v) {
            is JsonObject -> {
                val known = knownAt(path)
                out += "$path:" + v.keys.filter { it !in known }.joinToString(",")
                for ((k, c) in v.entries) unknownOrder(c, "$path/$k", out)
            }

            is JsonArray -> {
                v.forEachIndexed { i, c -> unknownOrder(c, "$path/$i", out) }
            }

            else -> {
                Unit
            }
        }
        return out.sorted()
    }

    private fun knownAt(path: String): Set<String> =
        when (path) {
            "" -> ThemeParser.TOP
            "/colors" -> ThemeParser.COLORS
            "/colors/source" -> ThemeParser.SOURCE
            "/colors/light", "/colors/dark" -> ThemeParser.PALETTE
            "/wallpaper" -> ThemeParser.WALLPAPER
            "/typography" -> ThemeParser.TYPOGRAPHY
            "/typography/family", "/typography/clockFamily" -> ThemeParser.FONT_REF
            "/wallpaper/image" -> ThemeParser.IMAGE
            "/list" -> ThemeParser.LIST
            "/favorites" -> ThemeParser.FAVORITES
            "/search" -> ThemeParser.SEARCH
            "/alphabetIndex" -> ThemeParser.ALPHABET_INDEX
            "/icons" -> ThemeParser.ICONS
            "/icons/pack" -> ThemeParser.PACK
            "/clock" -> ThemeParser.CLOCK
            "/clock/date" -> ThemeParser.DATE
            "/animation" -> ThemeParser.ANIMATION
            "/haptics" -> ThemeParser.HAPTICS
            else -> emptySet()
        }

    @Test
    fun `T-THEME-21 nested unknowns, x- keys and future enum values survive parse-write-parse`() {
        val bytes = ThemeFixtures.corpus("unknowns.strata-theme.json")
        val (first, second) = roundTrip(bytes)
        assertThat(
            first.issues.map {
                it.code
            },
        ).containsAtLeast(IssueCode.UNKNOWN_KEY, IssueCode.UNKNOWN_ENUM, IssueCode.NEWER_MINOR)
        assertThat(first.issues.none { it.severity == app.strata.launcher.core.theme.parse.Severity.ERROR }).isTrue()
        // JSON-equal to the first parse, and to the source file itself.
        assertThat(second.tree).isEqualTo(first.tree)
        assertThat(first.tree).isEqualTo(json(String(bytes)))
        assertThat(second.document).isEqualTo(first.document)
        // Order of unknown members preserved at every level.
        assertThat(unknownOrder(second.tree)).isEqualTo(unknownOrder(first.tree))
        assertThat(unknownOrder(first.tree)).contains(":x-first,futureTopLevel,x-last,zLastUnknown")
        // Future enum values are kept verbatim while defaults render.
        assertThat((second.tree["colors"] as JsonObject)["mode"]).isEqualTo(JsonString("sepia"))
        assertThat(second.document.colors.mode).isEqualTo(ColorMode.SYSTEM)
        assertThat(second.document.clock.style).isEqualTo(ClockStyle.NUMERIC)
        // Number lexemes are written back as they were.
        assertThat(ThemeWriter.writeString(first.tree)).contains("\"dim\": 0.70")
        assertThat(ThemeWriter.writeString(first.tree)).contains("1.50")
        // Second write is byte-identical (writer is idempotent).
        assertThat(ThemeWriter.write(second.tree)).isEqualTo(ThemeWriter.write(first.tree))
    }

    @Test
    fun `T-THEME-21 every sample round-trips`() {
        for ((name, bytes) in ThemeFixtures.samples()) {
            val (first, second) = roundTrip(bytes)
            assertWithMessage(name).that(second.tree).isEqualTo(first.tree)
            assertWithMessage(name).that(unknownOrder(second.tree)).isEqualTo(unknownOrder(first.tree))
        }
    }

    // ---- T-THEME-22 generator -------------------------------------------------------------------

    private class Gen(val r: Random) {
        var values = 0

        fun color(): Int = 0xFF000000.toInt() or r.nextInt(0x1000000)

        fun hex(n: Int) = (1..n).map { "0123456789abcdef"[r.nextInt(16)] }.joinToString("")

        fun uuid() = "${hex(8)}-${hex(4)}-4${hex(3)}-8${hex(3)}-${hex(12)}"

        fun text(max: Int): String {
            val pool = "abcXYZ 0123-_.éßøΩЖ日本語😀\"\\/"
            val n = r.nextInt(1, max + 1)
            val sb = StringBuilder()
            while (sb.codePointCount(0, sb.length) < n) {
                val cp =
                    pool.codePointAt(
                        r.nextInt(pool.length).let { if (Character.isLowSurrogate(pool[it])) it - 1 else it },
                    )
                sb.appendCodePoint(cp)
            }
            return sb.toString()
        }

        fun double(
            min: Double,
            max: Double,
        ) = (min + r.nextDouble() * (max - min)).let { Math.round(it * 1000) / 1000.0 }.coerceIn(min, max)

        inline fun <reified E : Enum<E>> pick(): E = enumValues<E>().let { it[r.nextInt(it.size)] }

        fun palette() = Palette.of(ColorRole.entries.associateWith { color() })

        fun font(): FontRef =
            when (r.nextInt(3)) {
                0 -> FontRef.System
                1 -> FontRef.Bundled("f" + hex(r.nextInt(1, 10)))
                else -> FontRef.User(hex(64), if (r.nextBoolean()) text(20) else null)
            }

        fun document(): ThemeDocument {
            val base = ThemeDocument(uuid(), text(30))
            val kind = pick<SourceKind>()
            return base.copy(
                schemaMinor = if (r.nextInt(4) == 0) r.nextInt(0, 1001) else 0,
                author = if (r.nextBoolean()) text(40) else null,
                description = if (r.nextBoolean()) text(100) else null,
                basedOn = if (r.nextBoolean()) uuid() else null,
                colors =
                    ColorsSpec(
                        mode = pick(),
                        sourceKind = kind,
                        seedColor = if (kind == SourceKind.SEED || r.nextBoolean()) color() else null,
                        lockedRoles = ColorRole.entries.filter { r.nextInt(4) == 0 }.toSet(),
                        pureBlack = r.nextBoolean(),
                        light = palette(),
                        dark = palette(),
                    ),
                wallpaper =
                    WallpaperSpec(
                        r.nextBoolean(),
                        double(0.0, 0.95),
                        double(0.0, 1.0),
                        if (r.nextBoolean()) {
                            WallpaperImage(
                                hex(64),
                                if (r.nextBoolean()) pick<ImageMediaType>() else null,
                            )
                        } else {
                            null
                        },
                    ),
                typography =
                    TypographySpec(
                        font(),
                        if (r.nextBoolean()) font() else null,
                        double(0.8, 1.5),
                        100 * r.nextInt(1, 10),
                        100 * r.nextInt(1, 10),
                        100 * r.nextInt(1, 10),
                        double(-0.05, 0.2),
                        pick<LabelCase>(),
                        r.nextBoolean(),
                    ),
                list =
                    ListSpec(
                        pick<Density>(),
                        if (r.nextBoolean()) r.nextInt(48, 97) else null,
                        r.nextInt(0, 49),
                        r.nextInt(0, 25),
                        r.nextBoolean(),
                        r.nextBoolean(),
                    ),
                icons =
                    base.icons.copy(
                        visible = r.nextBoolean(),
                        sizeDp = r.nextInt(20, 65),
                        shape = pick<IconShape>(),
                        cornerRadiusPercent = r.nextInt(0, 51),
                        pack =
                            if (r.nextBoolean()) {
                                IconPackRef(
                                    "com.p${hex(3)}.pack",
                                    if (r.nextBoolean()) text(20) else null,
                                    pick(),
                                )
                            } else {
                                null
                            },
                        tint = pick<IconTint>(),
                    ),
                clock =
                    base.clock.copy(
                        style = pick(),
                        sizeSp = r.nextInt(24, 129),
                        alignment = pick<Alignment>(),
                        date =
                            base.clock.date.copy(
                                skeleton =
                                    (1..r.nextInt(1, 17)).map { "yMLdEc"[r.nextInt(6)] }.joinToString(""),
                            ),
                    ),
                favorites =
                    base.favorites.copy(
                        textScale = double(0.8, 2.0),
                        spacingDp = r.nextInt(0, 33),
                        showIcons = r.nextBoolean(),
                    ),
                search = base.search.copy(position = pick(), style = pick(), cornerRadiusDp = r.nextInt(0, 29)),
                alphabetIndex =
                    base.alphabetIndex.copy(
                        visibility = pick(),
                        side = pick(),
                        glyphs = pick(),
                        textSizeSp = r.nextInt(10, 19),
                    ),
                animation = base.animation.copy(durationScale = double(0.0, 2.0), listEntrance = pick()),
                haptics = base.haptics.copy(mode = pick()),
            )
        }

        fun jsonValue(depth: Int): JsonValue {
            values++
            val leaf = depth >= 4 || values > 200 || r.nextInt(3) > 0
            return if (leaf) {
                when (r.nextInt(6)) {
                    0 -> JsonNull
                    1 -> JsonBool.of(r.nextBoolean())
                    2 -> JsonNumber.of(r.nextLong(-1_000_000, 1_000_000))
                    3 -> JsonNumber.parse(listOf("0.5", "1e3", "-2.250", "1E-7", "0", "-0", "123.4560")[r.nextInt(7)])
                    else -> JsonString(text(12))
                }
            } else if (r.nextBoolean()) {
                JsonArray((0 until r.nextInt(0, 5)).map { jsonValue(depth + 1) })
            } else {
                JsonObject((0 until r.nextInt(0, 5)).associate { "k${hex(3)}" to jsonValue(depth + 1) })
            }
        }

        fun unknownKey(): String =
            if (r.nextBoolean()) {
                "x-" + hex(1) + "ext." + hex(r.nextInt(1, 8))
            } else {
                "future" +
                    hex(5)
            }

        /** Inserts random unknown members at random positions of every object of the known shape. */
        fun sprinkle(
            obj: JsonObject,
            depth: Int = 1,
        ): JsonObject {
            val entries =
                obj.entries
                    .map { (k, v) ->
                        k to
                            (if (v is JsonObject && depth < 4) sprinkle(v, depth + 1) else v)
                    }.toMutableList()
            repeat(r.nextInt(0, 3)) {
                entries.add(r.nextInt(entries.size + 1), unknownKey() to jsonValue(depth + 1))
            }
            return JsonObject(linkedMapOf(*entries.distinctBy { it.first }.toTypedArray()))
        }
    }

    @Test
    fun `T-THEME-22 5000 generated documents with random unknown members round-trip`() {
        val gen = Gen(Random(20261029))
        repeat(5000) { case ->
            gen.values = 0
            val doc = gen.document()
            val tree = gen.sprinkle(ThemeEncoder.encode(doc, full = gen.r.nextBoolean()))
            val bytes =
                app.strata.launcher.core.json.JsonWriter
                    .prettyBytes(tree) // source order, not writer order
            val (first, second) = roundTrip(bytes)
            assertWithMessage("case $case")
                .that(
                    first.issues.none {
                        it.severity ==
                            app.strata.launcher.core.theme.parse.Severity.ERROR
                    },
                ).isTrue()
            assertWithMessage("case $case")
                .that(
                    first.issues.filter {
                        it.code != IssueCode.UNKNOWN_KEY &&
                            it.code != IssueCode.NEWER_MINOR
                    },
                ).isEmpty()
            assertWithMessage("case $case").that(first.tree).isEqualTo(tree)
            assertWithMessage("case $case").that(second.tree).isEqualTo(first.tree)
            assertWithMessage("case $case").that(second.document).isEqualTo(first.document)
            assertWithMessage("case $case").that(first.document).isEqualTo(doc)
            assertWithMessage("case $case").that(unknownOrder(second.tree)).isEqualTo(unknownOrder(first.tree))
        }
    }
}
