package app.strata.launcher.core.theme.parse

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.common.trace.TraceSections
import app.strata.launcher.core.common.trace.Tracer
import app.strata.launcher.core.common.trace.section
import app.strata.launcher.core.json.JsonArray
import app.strata.launcher.core.json.JsonBool
import app.strata.launcher.core.json.JsonError
import app.strata.launcher.core.json.JsonLimits
import app.strata.launcher.core.json.JsonNull
import app.strata.launcher.core.json.JsonNumber
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonParser
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.json.JsonValue
import app.strata.launcher.core.json.position
import app.strata.launcher.core.theme.color.Rgb
import app.strata.launcher.core.theme.model.AlphabetIndexSpec
import app.strata.launcher.core.theme.model.AnimationSpec
import app.strata.launcher.core.theme.model.BuiltInPalettes
import app.strata.launcher.core.theme.model.ClockSpec
import app.strata.launcher.core.theme.model.ColorRole
import app.strata.launcher.core.theme.model.ColorsSpec
import app.strata.launcher.core.theme.model.DateSpec
import app.strata.launcher.core.theme.model.FavoritesSpec
import app.strata.launcher.core.theme.model.FontRef
import app.strata.launcher.core.theme.model.HapticsSpec
import app.strata.launcher.core.theme.model.IconPackRef
import app.strata.launcher.core.theme.model.IconsSpec
import app.strata.launcher.core.theme.model.ImageMediaType
import app.strata.launcher.core.theme.model.ListSpec
import app.strata.launcher.core.theme.model.Palette
import app.strata.launcher.core.theme.model.SearchSpec
import app.strata.launcher.core.theme.model.SourceKind
import app.strata.launcher.core.theme.model.ThemeDocument
import app.strata.launcher.core.theme.model.ThemeSchema
import app.strata.launcher.core.theme.model.TypographySpec
import app.strata.launcher.core.theme.model.Variant
import app.strata.launcher.core.theme.model.WallpaperImage
import app.strata.launcher.core.theme.model.WallpaperSpec
import app.strata.launcher.core.theme.model.WireEnum
import app.strata.launcher.core.theme.model.wireValueOf
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * THEME_SYSTEM §8.1 stages 1–5: capped read, strict decode and parse ([JsonParser]), version gate and
 * migrations, then lenient structural validation with the per-field **On invalid** policies of §4.
 *
 * Never throws: rejections are [Outcome.Err]; every other problem becomes an [Issue] and a fallback.
 * Reference checks (stage 6) and contrast (stage 7) need platform inputs and live in the resolver.
 */
class ThemeParser(
    private val migrations: ThemeMigrations = ThemeMigrations.V1,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val limits: JsonLimits = JsonLimits.THEME,
    /** `strata.themeParse` around stages 1–5 for T-THEME-44 on a device; [Tracer.NONE] on the JVM. */
    private val tracer: Tracer = Tracer.NONE,
) {
    fun parse(bytes: ByteArray): Outcome<ParsedTheme, ThemeError> =
        tracer.section(TraceSections.THEME_PARSE) {
            when (val r = JsonParser.parse(bytes, limits)) {
                is Outcome.Ok -> validate(r.value)
                is Outcome.Err -> Outcome.Err(jsonError(r.error))
            }
        }

    fun parse(input: InputStream): Outcome<ParsedTheme, ThemeError> =
        tracer.section(TraceSections.THEME_PARSE) {
            when (val r = JsonParser.parse(input, limits)) {
                is Outcome.Ok -> validate(r.value)
                is Outcome.Err -> Outcome.Err(jsonError(r.error))
            }
        }

    /** Stages 4–5 on an already parsed tree. */
    fun validate(root: JsonValue): Outcome<ParsedTheme, ThemeError> {
        if (root !is JsonObject) return notATheme("The top level is not an object")
        val version = root["schemaVersion"]
        if (version !is JsonNumber ||
            !version.isIntegral
        ) {
            return notATheme("schemaVersion is missing or not an integer")
        }
        val v = version.toBigDecimal()
        if (v > BigDecimal.valueOf(migrations.targetVersion.toLong())) {
            val shown = if (v > BigDecimal.valueOf(Long.MAX_VALUE)) Long.MAX_VALUE else v.toLong()
            return Outcome.Err(
                ThemeError.NewerVersion(
                    Issue("/schemaVersion", IssueCode.NEWER_VERSION, "Made with a newer version"),
                    shown,
                ),
            )
        }
        if (v < BigDecimal.valueOf(migrations.oldestSupported.toLong())) return notATheme("schemaVersion is too old")
        val from = v.toInt()
        var tree: JsonObject = root
        val issues = ArrayList<Issue>()
        if (from < migrations.targetVersion) {
            tree =
                try {
                    migrations.migrate(root, from)
                } catch (e: RuntimeException) {
                    return Outcome.Err(ThemeError.NotATheme(Issue("", IssueCode.MIGRATION_FAILED, "Migration failed")))
                }
            issues += Issue("/schemaVersion", IssueCode.MIGRATED, "Migrated from schemaVersion $from")
        }
        return try {
            val (doc, fixedTree) = Validation(tree, issues).run()
            Outcome.Ok(
                ParsedTheme(
                    doc,
                    fixedTree,
                    issues.toList(),
                    if (from <
                        migrations.targetVersion
                    ) {
                        from
                    } else {
                        null
                    },
                ),
            )
        } catch (e: RuntimeException) {
            // Defensive: validation is total, but a bug must never escape the boundary (NFR-THEME-01).
            notATheme("Unreadable theme")
        }
    }

    private fun notATheme(msg: String): Outcome.Err<ThemeError> =
        Outcome.Err(
            ThemeError.NotATheme(Issue("", IssueCode.NOT_A_THEME, msg)),
        )

    private fun jsonError(e: JsonError): ThemeError {
        val pos = e.position
        return when (e) {
            is JsonError.TooLarge -> {
                ThemeError.TooLarge(Issue("", IssueCode.TOO_LARGE, "Larger than ${e.limitBytes} bytes"))
            }

            is JsonError.InvalidEncoding -> {
                ThemeError.Damaged(Issue("", IssueCode.INVALID_ENCODING, "Invalid UTF-8 at byte ${e.byteOffset}"))
            }

            is JsonError.Syntax -> {
                ThemeError.Damaged(
                    Issue("", IssueCode.MALFORMED_JSON, "Syntax error: ${e.kind}", pos?.first, pos?.second),
                )
            }

            is JsonError.DuplicateKey -> {
                ThemeError.Damaged(Issue("", IssueCode.DUPLICATE_KEY, "Duplicate key", pos?.first, pos?.second))
            }

            is JsonError.LimitExceeded -> {
                ThemeError.Damaged(
                    Issue("", IssueCode.LIMIT_EXCEEDED, "Limit exceeded: ${e.limit}", pos?.first, pos?.second),
                )
            }
        }
    }

    private inner class Validation(private val root: JsonObject, private val issues: MutableList<Issue>) {
        private var tree = root

        fun run(): Pair<ThemeDocument, JsonObject> {
            val top = Obj(root, "")
            val schemaMinor = schemaMinor(top)
            val id = id(top)
            val name = name(top)
            val author = text(top, "author", 64, allowLineFeed = false)
            val description = text(top, "description", 500, allowLineFeed = true)
            val createdAt = top.pattern("createdAt", TIMESTAMP, 40)
            val modifiedAt = top.pattern("modifiedAt", TIMESTAMP, 40)
            val basedOn = top.pattern("basedOn", UUID_RE, 36)
            top.raw("\$schema")?.let {
                if (it !is JsonString ||
                    it.value.length > 512
                ) {
                    top.issue("\$schema", IssueCode.INVALID_VALUE)
                }
            }
            val doc =
                ThemeDocument(
                    id = id,
                    name = name,
                    schemaVersion = migrations.targetVersion,
                    schemaMinor = schemaMinor,
                    author = author,
                    description = description,
                    createdAt = createdAt,
                    modifiedAt = modifiedAt,
                    basedOn = basedOn,
                    colors = colors(top.group("colors")),
                    wallpaper = wallpaper(top.group("wallpaper")),
                    typography = typography(top.group("typography")),
                    list = list(top.group("list")),
                    icons = icons(top.group("icons")),
                    clock = clock(top.group("clock")),
                    favorites = favorites(top.group("favorites")),
                    search = search(top.group("search")),
                    alphabetIndex = alphabetIndex(top.group("alphabetIndex")),
                    animation = animation(top.group("animation")),
                    haptics =
                        HapticsSpec(
                            top.group("haptics").let { g ->
                                g.enum("mode", HapticsSpec().mode).also { g.finish(HAPTICS) }
                            },
                        ),
                )
            top.finish(TOP)
            return doc to tree
        }

        private fun schemaMinor(top: Obj): Int {
            val raw = top.raw("schemaMinor") ?: return 0
            if (raw !is JsonNumber) return 0.also { top.issue("schemaMinor", IssueCode.WRONG_TYPE) }
            val d = raw.toBigDecimal()
            if (!raw.isIntegral || d < BigDecimal.ZERO || d > BigDecimal.valueOf(1000)) {
                return 0.also { top.issue("schemaMinor", IssueCode.INVALID_VALUE) }
            }
            val minor = d.toInt()
            if (minor > ThemeSchema.MINOR) issues += Issue("/schemaMinor", IssueCode.NEWER_MINOR, "Newer settings kept")
            return minor
        }

        private fun id(top: Obj): String {
            val raw = top.raw("id")
            if (raw is JsonString && UUID_RE.matches(raw.value)) return raw.value
            val generated = newId()
            top.issue("id", IssueCode.GENERATED)
            tree = tree.with("id", JsonString(generated))
            return generated
        }

        private fun name(top: Obj): String {
            val raw = top.raw("name")
            if (raw !is JsonString) return generatedName(top)
            var value = raw.value
            val cleaned = stripControls(value, allowLineFeed = false)
            if (cleaned != value) {
                top.issue("name", IssueCode.CONTROL_CHARS_REMOVED)
                value = cleaned
            }
            if (value.isEmpty()) return generatedName(top)
            if (Graphemes.count(value) > 64) {
                top.issue("name", IssueCode.TRUNCATED)
                value = Graphemes.truncate(value, 64)
            }
            if (value != raw.value) tree = tree.with("name", JsonString(value))
            return value
        }

        private fun generatedName(top: Obj): String {
            top.issue("name", IssueCode.GENERATED)
            tree = tree.with("name", JsonString(UNTITLED))
            return UNTITLED
        }

        private fun text(
            top: Obj,
            key: String,
            max: Int,
            allowLineFeed: Boolean,
        ): String? {
            val raw = top.raw(key) ?: return null
            if (raw !is JsonString) return null.also { top.issue(key, IssueCode.WRONG_TYPE) }
            var value = stripControls(raw.value, allowLineFeed)
            if (value != raw.value) top.issue(key, IssueCode.CONTROL_CHARS_REMOVED)
            if (Graphemes.count(value) > max) {
                top.issue(key, IssueCode.TRUNCATED)
                value = Graphemes.truncate(value, max)
            }
            if (value != raw.value) tree = tree.with(key, JsonString(value))
            return value
        }

        private fun colors(g: Obj): ColorsSpec {
            val d = ColorsSpec()
            val source = g.group("source")
            val kind = source.enum("kind", d.sourceKind)
            val seed = source.color("seedColor")
            val locked = lockedRoles(source)
            source.finish(SOURCE)
            if (kind == SourceKind.SEED && seed == null) {
                issues +=
                    Issue(
                        "${source.path}/seedColor",
                        IssueCode.SEED_MISSING,
                        "Seed kind without a valid seedColor; using fixed palettes",
                    )
            }
            val spec =
                ColorsSpec(
                    mode = g.enum("mode", d.mode),
                    sourceKind = kind,
                    seedColor = seed,
                    lockedRoles = locked,
                    pureBlack = g.bool("pureBlack", d.pureBlack),
                    light = palette(g.group("light"), Variant.LIGHT),
                    dark = palette(g.group("dark"), Variant.DARK),
                )
            g.finish(COLORS)
            return spec
        }

        private fun lockedRoles(source: Obj): Set<ColorRole> {
            val raw = source.raw("lockedRoles") ?: return emptySet()
            if (raw !is JsonArray) {
                return emptySet<ColorRole>().also {
                    source.issue(
                        "lockedRoles",
                        IssueCode.WRONG_TYPE,
                    )
                }
            }
            val out = LinkedHashSet<ColorRole>()
            raw.forEachIndexed { i, item ->
                val role = (item as? JsonString)?.let { wireValueOf<ColorRole>(it.value) }
                if (role == null || !out.add(role)) source.issue("lockedRoles/$i", IssueCode.ITEM_DROPPED)
            }
            return out
        }

        private fun palette(
            g: Obj,
            variant: Variant,
        ): Palette {
            val base = BuiltInPalettes.of(variant)
            val roles = ColorRole.entries.associateWith { role -> g.color(role.wire) ?: base[role] }
            g.finish(PALETTE)
            return Palette.of(roles)
        }

        private fun wallpaper(g: Obj): WallpaperSpec {
            val d = WallpaperSpec()
            val spec =
                WallpaperSpec(
                    show = g.bool("show", d.show),
                    dim = g.number("dim", 0.0, 0.95, d.dim),
                    backgroundOpacity = g.number("backgroundOpacity", 0.0, 1.0, d.backgroundOpacity),
                    image = image(g),
                )
            g.finish(WALLPAPER)
            return spec
        }

        private fun image(g: Obj): WallpaperImage? {
            if (g.raw("image") == null) return null
            val img = g.group("image")
            if (img.obj == null) return null
            val sha = img.pattern("sha256", SHA256, 64)
            val mediaType = img.enumOrNull<ImageMediaType>("mediaType")
            img.finish(IMAGE)
            if (sha == null && "sha256" !in img.obj) img.issue("sha256", IssueCode.INVALID_VALUE)
            return sha?.let { WallpaperImage(it, mediaType) }
        }

        private fun typography(g: Obj): TypographySpec {
            val d = TypographySpec()
            val spec =
                TypographySpec(
                    family = fontRef(g, "family") ?: d.family,
                    clockFamily = fontRef(g, "clockFamily"),
                    scale = g.number("scale", 0.8, 1.5, d.scale),
                    labelWeight = g.weight("labelWeight", d.labelWeight),
                    headingWeight = g.weight("headingWeight", d.headingWeight),
                    clockWeight = g.weight("clockWeight", d.clockWeight),
                    letterSpacingEm = g.number("letterSpacingEm", -0.05, 0.20, d.letterSpacingEm),
                    labelCase = g.enum("labelCase", d.labelCase),
                    smallTextUsesSystemFont = g.bool("smallTextUsesSystemFont", d.smallTextUsesSystemFont),
                )
            g.finish(TYPOGRAPHY)
            return spec
        }

        /** null when absent or invalid (caller applies the default; invalid → issue). */
        private fun fontRef(
            parent: Obj,
            key: String,
        ): FontRef? {
            if (parent.raw(key) == null) return null
            val f = parent.group(key)
            if (f.obj == null) return null
            val kindRaw = f.raw("kind")
            val ref: FontRef? =
                when {
                    kindRaw == null -> {
                        null.also { f.issue("kind", IssueCode.INVALID_VALUE) }
                    }

                    kindRaw !is JsonString -> {
                        null.also { f.issue("kind", IssueCode.WRONG_TYPE) }
                    }

                    kindRaw.value == "system" -> {
                        FontRef.System
                    }

                    kindRaw.value == "bundled" -> {
                        f.pattern("id", BUNDLED_FONT_ID, 40)?.let { FontRef.Bundled(it) }
                            ?: null.also { if ("id" !in f.obj) f.issue("id", IssueCode.INVALID_VALUE) }
                    }

                    kindRaw.value == "user" -> {
                        f.pattern("sha256", SHA256, 64)?.let { FontRef.User(it, f.string("displayName", 64)) }
                            ?: null.also { if ("sha256" !in f.obj) f.issue("sha256", IssueCode.INVALID_VALUE) }
                    }

                    else -> {
                        null.also { f.issue("kind", IssueCode.UNKNOWN_ENUM) }
                    }
                }
            // Validate the remaining optional members even when unused, so their issues are reported.
            if (ref !is FontRef.Bundled) f.pattern("id", BUNDLED_FONT_ID, 40)
            if (ref !is FontRef.User) {
                f.pattern("sha256", SHA256, 64)
                f.string("displayName", 64)
            }
            f.finish(FONT_REF)
            return ref
        }

        private fun list(g: Obj): ListSpec {
            val d = ListSpec()
            val spec =
                ListSpec(
                    density = g.enum("density", d.density),
                    rowHeightDp = g.intOrNull("rowHeightDp", 48, 96),
                    horizontalPaddingDp = g.int("horizontalPaddingDp", 0, 48, d.horizontalPaddingDp),
                    itemSpacingDp = g.int("itemSpacingDp", 0, 24, d.itemSpacingDp),
                    sectionHeaders = g.bool("sectionHeaders", d.sectionHeaders),
                    showDividers = g.bool("showDividers", d.showDividers),
                )
            g.finish(LIST)
            return spec
        }

        private fun icons(g: Obj): IconsSpec {
            val d = IconsSpec()
            val spec =
                IconsSpec(
                    visible = g.bool("visible", d.visible),
                    sizeDp = g.int("sizeDp", 20, 64, d.sizeDp),
                    shape = g.enum("shape", d.shape),
                    cornerRadiusPercent = g.int("cornerRadiusPercent", 0, 50, d.cornerRadiusPercent),
                    pack = pack(g),
                    tint = g.enum("tint", d.tint),
                    legacyIconPlate = g.bool("legacyIconPlate", d.legacyIconPlate),
                    labelsVisible = g.bool("labelsVisible", d.labelsVisible),
                )
            g.finish(ICONS)
            return spec
        }

        private fun pack(g: Obj): IconPackRef? {
            val raw = g.raw("pack")
            if (raw == null || raw == JsonNull) return null
            val p = g.group("pack")
            if (p.obj == null) return null
            val pkg = p.pattern("package", PACKAGE, 255)
            if (pkg == null && "package" !in p.obj) p.issue("package", IssueCode.INVALID_VALUE)
            val ref =
                IconPackRef(
                    packageName = pkg ?: "",
                    displayName = p.string("displayName", 64),
                    fallback = p.enum("fallback", IconPackRef("").fallback),
                )
            p.finish(PACK)
            return if (pkg == null) null else ref
        }

        private fun clock(g: Obj): ClockSpec {
            val d = ClockSpec()
            val date = g.group("date")
            val dateSpec =
                DateSpec(
                    visible = date.bool("visible", d.date.visible),
                    skeleton = date.pattern("skeleton", SKELETON, 16) ?: d.date.skeleton,
                    sizeSp = date.int("sizeSp", 12, 32, d.date.sizeSp),
                )
            date.finish(DATE)
            val spec =
                ClockSpec(
                    visible = g.bool("visible", d.visible),
                    style = g.enum("style", d.style),
                    hourFormat = g.enum("hourFormat", d.hourFormat),
                    showSeconds = g.bool("showSeconds", d.showSeconds),
                    sizeSp = g.int("sizeSp", 24, 128, d.sizeSp),
                    alignment = g.enum("alignment", d.alignment),
                    date = dateSpec,
                )
            g.finish(CLOCK)
            return spec
        }

        private fun favorites(g: Obj): FavoritesSpec {
            val d = FavoritesSpec()
            val spec =
                FavoritesSpec(
                    textScale = g.number("textScale", 0.8, 2.0, d.textScale),
                    alignment = g.enum("alignment", d.alignment),
                    verticalPosition = g.enum("verticalPosition", d.verticalPosition),
                    showIcons = g.bool("showIcons", d.showIcons),
                    showLabels = g.bool("showLabels", d.showLabels),
                    spacingDp = g.int("spacingDp", 0, 32, d.spacingDp),
                )
            g.finish(FAVORITES)
            return spec
        }

        private fun search(g: Obj): SearchSpec {
            val d = SearchSpec()
            val spec =
                SearchSpec(
                    position = g.enum("position", d.position),
                    style = g.enum("style", d.style),
                    cornerRadiusDp = g.int("cornerRadiusDp", 0, 28, d.cornerRadiusDp),
                    showHint = g.bool("showHint", d.showHint),
                )
            g.finish(SEARCH)
            return spec
        }

        private fun alphabetIndex(g: Obj): AlphabetIndexSpec {
            val d = AlphabetIndexSpec()
            val spec =
                AlphabetIndexSpec(
                    visibility = g.enum("visibility", d.visibility),
                    side = g.enum("side", d.side),
                    glyphs = g.enum("glyphs", d.glyphs),
                    textSizeSp = g.int("textSizeSp", 10, 18, d.textSizeSp),
                    showBubble = g.bool("showBubble", d.showBubble),
                )
            g.finish(ALPHABET_INDEX)
            return spec
        }

        private fun animation(g: Obj): AnimationSpec {
            val d = AnimationSpec()
            val spec =
                AnimationSpec(
                    durationScale = g.number("durationScale", 0.0, 2.0, d.durationScale),
                    listEntrance = g.enum("listEntrance", d.listEntrance),
                )
            g.finish(ANIMATION)
            return spec
        }

        /** Reader over one JSON object; `obj == null` means absent or invalid (all fields default). */
        private inner class Obj(val obj: JsonObject?, val path: String) {
            fun raw(key: String): JsonValue? = obj?.get(key)

            fun issue(
                key: String,
                code: IssueCode,
            ) {
                issues += Issue("$path/$key", code, describe(code))
            }

            fun group(key: String): Obj {
                val raw = raw(key)
                if (raw == null) return Obj(null, "$path/$key")
                if (raw !is JsonObject) {
                    issue(key, IssueCode.WRONG_TYPE)
                    return Obj(null, "$path/$key")
                }
                return Obj(raw, "$path/$key")
            }

            fun bool(
                key: String,
                default: Boolean,
            ): Boolean =
                when (val r = raw(key)) {
                    null -> default
                    is JsonBool -> r.value
                    else -> default.also { issue(key, IssueCode.WRONG_TYPE) }
                }

            inline fun <reified E> enum(
                key: String,
                default: E,
            ): E where E : Enum<E>, E : WireEnum = enumOrNull<E>(key) ?: default

            inline fun <reified E> enumOrNull(key: String): E? where E : Enum<E>, E : WireEnum =
                when (val r = raw(key)) {
                    null -> null
                    is JsonString -> wireValueOf<E>(r.value) ?: null.also { issue(key, IssueCode.UNKNOWN_ENUM) }
                    else -> null.also { issue(key, IssueCode.WRONG_TYPE) }
                }

            fun number(
                key: String,
                min: Double,
                max: Double,
                default: Double,
            ): Double {
                val r = raw(key) ?: return default
                if (r !is JsonNumber) return default.also { issue(key, IssueCode.WRONG_TYPE) }
                val v = r.toDouble()
                if (v < min || v > max) {
                    issue(key, IssueCode.CLAMPED)
                    return v.coerceIn(min, max)
                }
                return v
            }

            fun intOrNull(
                key: String,
                min: Int,
                max: Int,
            ): Int? {
                val r = raw(key) ?: return null
                if (r !is JsonNumber) return null.also { issue(key, IssueCode.WRONG_TYPE) }
                var d = r.toBigDecimal()
                if (!r.isIntegral) {
                    issue(key, IssueCode.ROUNDED)
                    d = d.setScale(0, RoundingMode.HALF_EVEN)
                }
                if (d < BigDecimal.valueOf(min.toLong()) || d > BigDecimal.valueOf(max.toLong())) {
                    issue(key, IssueCode.CLAMPED)
                    return if (d < BigDecimal.valueOf(min.toLong())) min else max
                }
                return d.toInt()
            }

            fun int(
                key: String,
                min: Int,
                max: Int,
                default: Int,
            ): Int = intOrNull(key, min, max) ?: default

            fun weight(
                key: String,
                default: Int,
            ): Int {
                val r = raw(key) ?: return default
                if (r !is JsonNumber) return default.also { issue(key, IssueCode.WRONG_TYPE) }
                var d = r.toBigDecimal().setScale(0, RoundingMode.HALF_EVEN)
                val hundreds =
                    d
                        .divide(
                            BigDecimal.valueOf(100),
                        ).setScale(0, RoundingMode.HALF_EVEN)
                        .multiply(BigDecimal.valueOf(100))
                if (!r.isIntegral || hundreds.compareTo(d) != 0) issue(key, IssueCode.ROUNDED)
                d = hundreds
                if (d < BigDecimal.valueOf(100) || d > BigDecimal.valueOf(900)) {
                    issue(key, IssueCode.CLAMPED)
                    return if (d < BigDecimal.valueOf(100)) 100 else 900
                }
                return d.toInt()
            }

            fun color(key: String): Int? =
                when (val r = raw(key)) {
                    null -> null
                    is JsonString -> Rgb.parseHex(r.value) ?: null.also { issue(key, IssueCode.INVALID_VALUE) }
                    else -> null.also { issue(key, IssueCode.WRONG_TYPE) }
                }

            /** String matching [re] and at most [maxLen] chars, else D (null + issue). */
            fun pattern(
                key: String,
                re: Regex,
                maxLen: Int,
            ): String? =
                when (val r = raw(key)) {
                    null -> {
                        null
                    }

                    is JsonString -> {
                        if (r.value.length <= maxLen &&
                            re.matches(r.value)
                        ) {
                            r.value
                        } else {
                            null.also { issue(key, IssueCode.INVALID_VALUE) }
                        }
                    }

                    else -> {
                        null.also { issue(key, IssueCode.WRONG_TYPE) }
                    }
                }

            /** Free string, T beyond [maxGraphemes]. */
            fun string(
                key: String,
                maxGraphemes: Int,
            ): String? =
                when (val r = raw(key)) {
                    null -> {
                        null
                    }

                    is JsonString -> {
                        if (Graphemes.count(r.value) > maxGraphemes) {
                            issue(key, IssueCode.TRUNCATED)
                            Graphemes.truncate(r.value, maxGraphemes)
                        } else {
                            r.value
                        }
                    }

                    else -> {
                        null.also { issue(key, IssueCode.WRONG_TYPE) }
                    }
                }

            /** Unknown-key warnings (§8.1 stage 5: `additionalProperties` → warning; members are preserved). */
            fun finish(known: Set<String>) {
                val o = obj ?: return
                for (k in o.keys) {
                    if (k !in known && !X_KEY.matches(k)) {
                        issues +=
                            Issue("$path/${escapePointer(k)}", IssueCode.UNKNOWN_KEY, "Unknown key kept: ${k.take(32)}")
                    }
                }
            }
        }
    }

    companion object {
        const val UNTITLED = "Untitled theme"

        val X_KEY = Regex("^x-[a-z0-9][a-z0-9._-]{0,62}$")
        val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val TIMESTAMP =
            Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})$")
        val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        val BUNDLED_FONT_ID = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
        val SKELETON = Regex("^[yMLdEc]{1,16}$")

        // Known keys per object, in schema order (also used by the writer).
        val TOP =
            linkedSetOf(
                "\$schema",
                "schemaVersion",
                "schemaMinor",
                "id",
                "name",
                "author",
                "description",
                "createdAt",
                "modifiedAt",
                "basedOn",
                "colors",
                "wallpaper",
                "typography",
                "list",
                "icons",
                "clock",
                "favorites",
                "search",
                "alphabetIndex",
                "animation",
                "haptics",
            )
        val COLORS = linkedSetOf("mode", "source", "pureBlack", "light", "dark")
        val SOURCE = linkedSetOf("kind", "seedColor", "lockedRoles")
        val PALETTE = ColorRole.entries.mapTo(LinkedHashSet()) { it.wire }
        val WALLPAPER = linkedSetOf("show", "dim", "backgroundOpacity", "image")
        val IMAGE = linkedSetOf("sha256", "mediaType")
        val FONT_REF = linkedSetOf("kind", "id", "sha256", "displayName")
        val TYPOGRAPHY =
            linkedSetOf(
                "family",
                "clockFamily",
                "scale",
                "labelWeight",
                "headingWeight",
                "clockWeight",
                "letterSpacingEm",
                "labelCase",
                "smallTextUsesSystemFont",
            )
        val LIST =
            linkedSetOf(
                "density",
                "rowHeightDp",
                "horizontalPaddingDp",
                "itemSpacingDp",
                "sectionHeaders",
                "showDividers",
            )
        val ICONS =
            linkedSetOf(
                "visible",
                "sizeDp",
                "shape",
                "cornerRadiusPercent",
                "pack",
                "tint",
                "legacyIconPlate",
                "labelsVisible",
            )
        val PACK = linkedSetOf("package", "displayName", "fallback")
        val CLOCK = linkedSetOf("visible", "style", "hourFormat", "showSeconds", "sizeSp", "alignment", "date")
        val DATE = linkedSetOf("visible", "skeleton", "sizeSp")
        val FAVORITES =
            linkedSetOf("textScale", "alignment", "verticalPosition", "showIcons", "showLabels", "spacingDp")
        val SEARCH = linkedSetOf("position", "style", "cornerRadiusDp", "showHint")
        val ALPHABET_INDEX = linkedSetOf("visibility", "side", "glyphs", "textSizeSp", "showBubble")
        val ANIMATION = linkedSetOf("durationScale", "listEntrance")
        val HAPTICS = linkedSetOf("mode")

        /** Removes Unicode `Cc` characters (keeps LF when [allowLineFeed]). */
        fun stripControls(
            s: String,
            allowLineFeed: Boolean,
        ): String {
            if (s.none { Character.getType(it) == Character.CONTROL.toInt() }) return s
            return s.filter { Character.getType(it) != Character.CONTROL.toInt() || (allowLineFeed && it == '\n') }
        }

        internal fun escapePointer(k: String): String = k.take(64).replace("~", "~0").replace("/", "~1")

        private fun describe(code: IssueCode): String =
            when (code) {
                IssueCode.CLAMPED -> "Out of range; clamped"
                IssueCode.ROUNDED -> "Rounded to an allowed value"
                IssueCode.WRONG_TYPE -> "Wrong type; default used"
                IssueCode.INVALID_VALUE -> "Invalid value; default used"
                IssueCode.UNKNOWN_ENUM -> "Unknown value kept; default used"
                IssueCode.ITEM_DROPPED -> "Invalid item dropped"
                IssueCode.GENERATED -> "Missing or invalid; generated"
                IssueCode.TRUNCATED -> "Too long; truncated"
                IssueCode.CONTROL_CHARS_REMOVED -> "Control characters removed"
                else -> code.name
            }
    }
}
