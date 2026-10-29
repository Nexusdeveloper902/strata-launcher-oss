package app.strata.launcher.core.theme

import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonParser
import app.strata.launcher.core.json.JsonValue
import app.strata.launcher.core.json.JsonWriter
import app.strata.launcher.core.theme.model.Alignment
import app.strata.launcher.core.theme.model.BuiltInPalettes
import app.strata.launcher.core.theme.model.ClockStyle
import app.strata.launcher.core.theme.model.ColorMode
import app.strata.launcher.core.theme.model.ColorRole
import app.strata.launcher.core.theme.model.Density
import app.strata.launcher.core.theme.model.FontRef
import app.strata.launcher.core.theme.model.HapticsMode
import app.strata.launcher.core.theme.model.HourFormat
import app.strata.launcher.core.theme.model.IconShape
import app.strata.launcher.core.theme.model.IconTint
import app.strata.launcher.core.theme.model.IndexGlyphs
import app.strata.launcher.core.theme.model.IndexSide
import app.strata.launcher.core.theme.model.IndexVisibility
import app.strata.launcher.core.theme.model.LabelCase
import app.strata.launcher.core.theme.model.ListEntrance
import app.strata.launcher.core.theme.model.PackFallback
import app.strata.launcher.core.theme.model.SearchPosition
import app.strata.launcher.core.theme.model.SearchStyle
import app.strata.launcher.core.theme.model.SourceKind
import app.strata.launcher.core.theme.model.ThemeDocument
import app.strata.launcher.core.theme.model.VerticalPosition
import app.strata.launcher.core.theme.parse.Graphemes
import app.strata.launcher.core.theme.parse.IssueCode
import app.strata.launcher.core.theme.parse.Policy
import app.strata.launcher.core.theme.parse.Severity
import app.strata.launcher.core.theme.parse.ThemeError
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** T-THEME-20: one fixture per "On invalid" row of THEME_SYSTEM §4, plus the `name` fixtures. */
class PolicyTableTest {
    private data class Row(
        val path: String,
        val value: String,
        val code: IssueCode?,
        val expected: Any?,
        val get: (ThemeDocument) -> Any?,
    )

    private fun row(
        path: String,
        value: String,
        code: IssueCode?,
        expected: Any?,
        get: (ThemeDocument) -> Any?,
    ) = Row(path, value, code, expected, get)

    private val rows =
        listOf(
            // §4.1 top level
            row("schemaMinor", "\"x\"", IssueCode.WRONG_TYPE, 0) { it.schemaMinor },
            row("schemaMinor", "1001", IssueCode.INVALID_VALUE, 0) { it.schemaMinor },
            row("schemaMinor", "-1", IssueCode.INVALID_VALUE, 0) { it.schemaMinor },
            row("schemaMinor", "0.5", IssueCode.INVALID_VALUE, 0) { it.schemaMinor },
            row("schemaMinor", "7", IssueCode.NEWER_MINOR, 7) { it.schemaMinor },
            row("id", "\"nope\"", IssueCode.GENERATED, "00000000-0000-4000-8000-000000000001") { it.id },
            row("id", "42", IssueCode.GENERATED, "00000000-0000-4000-8000-000000000001") { it.id },
            row(
                "id",
                "\"ABCDEF00-0000-4000-8000-000000000000\"",
                null,
                "ABCDEF00-0000-4000-8000-000000000000",
            ) { it.id },
            row("name", "\"\"", IssueCode.GENERATED, "Untitled theme") { it.name },
            row("name", "7", IssueCode.GENERATED, "Untitled theme") { it.name },
            row("name", "\"\\u0007\"", IssueCode.CONTROL_CHARS_REMOVED, "Untitled theme") { it.name },
            row("author", "\"${"a".repeat(65)}\"", IssueCode.TRUNCATED, "a".repeat(64)) { it.author },
            row("author", "\"a\\u0001b\"", IssueCode.CONTROL_CHARS_REMOVED, "ab") { it.author },
            row("author", "5", IssueCode.WRONG_TYPE, null) { it.author },
            row("description", "\"${"d".repeat(501)}\"", IssueCode.TRUNCATED, "d".repeat(500)) { it.description },
            row("description", "\"line\\nline\"", null, "line\nline") { it.description },
            row("description", "\"tab\\there\"", IssueCode.CONTROL_CHARS_REMOVED, "tabhere") { it.description },
            row("createdAt", "\"yesterday\"", IssueCode.INVALID_VALUE, null) { it.createdAt },
            row("modifiedAt", "\"2026-10-29T10:00:00Z\"", null, "2026-10-29T10:00:00Z") { it.modifiedAt },
            row("modifiedAt", "true", IssueCode.WRONG_TYPE, null) { it.modifiedAt },
            row("basedOn", "\"nope\"", IssueCode.INVALID_VALUE, null) { it.basedOn },
            row("\$schema", "5", IssueCode.INVALID_VALUE, null) { null },
            row("x-anything", "{\"deep\":[1,2]}", null, null) { null },
            row("unknownTop", "1", IssueCode.UNKNOWN_KEY, null) { null },
            row("X-bad", "1", IssueCode.UNKNOWN_KEY, null) { null },
            // groups that are not objects → D for the whole group
            row("colors", "5", IssueCode.WRONG_TYPE, BuiltInPalettes.LIGHT) { it.colors.light },
            row("wallpaper", "[]", IssueCode.WRONG_TYPE, 0.5) { it.wallpaper.dim },
            row("typography", "\"big\"", IssueCode.WRONG_TYPE, 1.0) { it.typography.scale },
            row("list", "true", IssueCode.WRONG_TYPE, Density.COMFORTABLE) { it.list.density },
            row("icons", "null", IssueCode.WRONG_TYPE, 32) { it.icons.sizeDp },
            row("clock", "1", IssueCode.WRONG_TYPE, 56) { it.clock.sizeSp },
            row("favorites", "1", IssueCode.WRONG_TYPE, 1.3) { it.favorites.textScale },
            row("search", "1", IssueCode.WRONG_TYPE, SearchPosition.BOTTOM) { it.search.position },
            row("alphabetIndex", "1", IssueCode.WRONG_TYPE, 12) { it.alphabetIndex.textSizeSp },
            row("animation", "1", IssueCode.WRONG_TYPE, 1.0) { it.animation.durationScale },
            row("haptics", "1", IssueCode.WRONG_TYPE, HapticsMode.FOLLOW_SYSTEM) { it.haptics.mode },
            // §4.2 colors
            row("colors/mode", "\"sepia\"", IssueCode.UNKNOWN_ENUM, ColorMode.SYSTEM) { it.colors.mode },
            row("colors/mode", "3", IssueCode.WRONG_TYPE, ColorMode.SYSTEM) { it.colors.mode },
            row("colors/source/kind", "\"lava\"", IssueCode.UNKNOWN_ENUM, SourceKind.FIXED) { it.colors.sourceKind },
            row("colors/source/seedColor", "\"#12\"", IssueCode.INVALID_VALUE, null) { it.colors.seedColor },
            row(
                "colors/source/lockedRoles",
                "[\"foo\",\"accent\",\"accent\",3]",
                IssueCode.ITEM_DROPPED,
                setOf(ColorRole.ACCENT),
            ) {
                it.colors.lockedRoles
            },
            row(
                "colors/source/lockedRoles",
                "\"accent\"",
                IssueCode.WRONG_TYPE,
                emptySet<ColorRole>(),
            ) { it.colors.lockedRoles },
            row(
                "colors/source",
                "{\"kind\":\"seed\"}",
                IssueCode.SEED_MISSING,
                SourceKind.SEED,
            ) { it.colors.sourceKind },
            row("colors/pureBlack", "\"yes\"", IssueCode.WRONG_TYPE, false) { it.colors.pureBlack },
            row("colors/light/accent", "\"#12345\"", IssueCode.INVALID_VALUE, BuiltInPalettes.LIGHT.accent) {
                it.colors.light.accent
            },
            row("colors/light/accent", "\"#aabbcc\"", null, 0xFFAABBCC.toInt()) { it.colors.light.accent },
            row("colors/dark/scrim", "5", IssueCode.WRONG_TYPE, BuiltInPalettes.DARK.scrim) { it.colors.dark.scrim },
            row("colors/dark/glow", "\"#000000\"", IssueCode.UNKNOWN_KEY, BuiltInPalettes.DARK) { it.colors.dark },
            row("colors/light", "\"x\"", IssueCode.WRONG_TYPE, BuiltInPalettes.LIGHT) { it.colors.light },
            // §4.3 wallpaper
            row("wallpaper/show", "1", IssueCode.WRONG_TYPE, false) { it.wallpaper.show },
            row("wallpaper/dim", "1.2", IssueCode.CLAMPED, 0.95) { it.wallpaper.dim },
            row("wallpaper/dim", "-1", IssueCode.CLAMPED, 0.0) { it.wallpaper.dim },
            row("wallpaper/backgroundOpacity", "2", IssueCode.CLAMPED, 1.0) { it.wallpaper.backgroundOpacity },
            row(
                "wallpaper/backgroundOpacity",
                "\"half\"",
                IssueCode.WRONG_TYPE,
                0.0,
            ) { it.wallpaper.backgroundOpacity },
            row("wallpaper/image", "{\"sha256\":\"xyz\"}", IssueCode.INVALID_VALUE, null) { it.wallpaper.image },
            row("wallpaper/image", "{}", IssueCode.INVALID_VALUE, null) { it.wallpaper.image },
            row(
                "wallpaper/image",
                "{\"sha256\":\"${"b".repeat(64)}\",\"mediaType\":\"image/gif\"}",
                IssueCode.UNKNOWN_ENUM,
                null,
            ) {
                it.wallpaper.image?.mediaType
            },
            // §4.4 typography
            row("typography/family", "\"Roboto\"", IssueCode.WRONG_TYPE, FontRef.System) { it.typography.family },
            row(
                "typography/family",
                "{\"kind\":\"emoji\"}",
                IssueCode.UNKNOWN_ENUM,
                FontRef.System,
            ) { it.typography.family },
            row("typography/family", "{}", IssueCode.INVALID_VALUE, FontRef.System) { it.typography.family },
            row(
                "typography/family",
                "{\"kind\":\"bundled\"}",
                IssueCode.INVALID_VALUE,
                FontRef.System,
            ) { it.typography.family },
            row(
                "typography/family",
                "{\"kind\":\"bundled\",\"id\":\"Bad_ID\"}",
                IssueCode.INVALID_VALUE,
                FontRef.System,
            ) {
                it.typography.family
            },
            row(
                "typography/family",
                "{\"kind\":\"user\"}",
                IssueCode.INVALID_VALUE,
                FontRef.System,
            ) { it.typography.family },
            row(
                "typography/clockFamily",
                "{\"kind\":\"bundled\",\"id\":\"serif-1\"}",
                null,
                FontRef.Bundled("serif-1"),
            ) {
                it.typography.clockFamily
            },
            row("typography/scale", "0.5", IssueCode.CLAMPED, 0.8) { it.typography.scale },
            row("typography/scale", "3", IssueCode.CLAMPED, 1.5) { it.typography.scale },
            row("typography/labelWeight", "450", IssueCode.ROUNDED, 400) { it.typography.labelWeight },
            row("typography/headingWeight", "333.3", IssueCode.ROUNDED, 300) { it.typography.headingWeight },
            row("typography/clockWeight", "1000", IssueCode.CLAMPED, 900) { it.typography.clockWeight },
            row("typography/clockWeight", "0", IssueCode.CLAMPED, 100) { it.typography.clockWeight },
            row("typography/labelWeight", "\"bold\"", IssueCode.WRONG_TYPE, 400) { it.typography.labelWeight },
            row("typography/letterSpacingEm", "1", IssueCode.CLAMPED, 0.2) { it.typography.letterSpacingEm },
            row(
                "typography/labelCase",
                "\"camel\"",
                IssueCode.UNKNOWN_ENUM,
                LabelCase.AS_IS,
            ) { it.typography.labelCase },
            row("typography/smallTextUsesSystemFont", "\"true\"", IssueCode.WRONG_TYPE, false) {
                it.typography.smallTextUsesSystemFont
            },
            // §4.5 list
            row("list/density", "\"huge\"", IssueCode.UNKNOWN_ENUM, Density.COMFORTABLE) { it.list.density },
            row("list/rowHeightDp", "40", IssueCode.CLAMPED, 48) { it.list.rowHeightDp },
            row("list/rowHeightDp", "50.5", IssueCode.ROUNDED, 50) { it.list.rowHeightDp },
            row("list/rowHeightDp", "60.0", null, 60) { it.list.rowHeightDp },
            row("list/horizontalPaddingDp", "100", IssueCode.CLAMPED, 48) { it.list.horizontalPaddingDp },
            row("list/itemSpacingDp", "-3", IssueCode.CLAMPED, 0) { it.list.itemSpacingDp },
            row("list/sectionHeaders", "0", IssueCode.WRONG_TYPE, true) { it.list.sectionHeaders },
            row("list/showDividers", "null", IssueCode.WRONG_TYPE, false) { it.list.showDividers },
            // §4.6 icons
            row("icons/visible", "\"no\"", IssueCode.WRONG_TYPE, true) { it.icons.visible },
            row("icons/sizeDp", "10", IssueCode.CLAMPED, 20) { it.icons.sizeDp },
            row("icons/shape", "\"star\"", IssueCode.UNKNOWN_ENUM, IconShape.SYSTEM) { it.icons.shape },
            row("icons/cornerRadiusPercent", "80", IssueCode.CLAMPED, 50) { it.icons.cornerRadiusPercent },
            row("icons/pack", "5", IssueCode.WRONG_TYPE, null) { it.icons.pack },
            row("icons/pack", "{\"package\":\"nodots\"}", IssueCode.INVALID_VALUE, null) { it.icons.pack },
            row("icons/pack", "{}", IssueCode.INVALID_VALUE, null) { it.icons.pack },
            row(
                "icons/pack",
                "{\"package\":\"com.a.b\",\"fallback\":\"x\"}",
                IssueCode.UNKNOWN_ENUM,
                PackFallback.SYSTEM,
            ) {
                it.icons.pack?.fallback
            },
            row(
                "icons/pack",
                "{\"package\":\"com.a.b\",\"displayName\":\"${"n".repeat(70)}\"}",
                IssueCode.TRUNCATED,
                "n".repeat(64),
            ) {
                it.icons.pack?.displayName
            },
            row("icons/tint", "\"red\"", IssueCode.UNKNOWN_ENUM, IconTint.NONE) { it.icons.tint },
            row("icons/legacyIconPlate", "1", IssueCode.WRONG_TYPE, true) { it.icons.legacyIconPlate },
            row("icons/labelsVisible", "1", IssueCode.WRONG_TYPE, true) { it.icons.labelsVisible },
            // §4.7 clock
            row("clock/visible", "1", IssueCode.WRONG_TYPE, true) { it.clock.visible },
            row("clock/style", "\"binary\"", IssueCode.UNKNOWN_ENUM, ClockStyle.NUMERIC) { it.clock.style },
            row("clock/hourFormat", "\"h36\"", IssueCode.UNKNOWN_ENUM, HourFormat.SYSTEM) { it.clock.hourFormat },
            row("clock/showSeconds", "1", IssueCode.WRONG_TYPE, false) { it.clock.showSeconds },
            row("clock/sizeSp", "200", IssueCode.CLAMPED, 128) { it.clock.sizeSp },
            row("clock/alignment", "\"left\"", IssueCode.UNKNOWN_ENUM, Alignment.START) { it.clock.alignment },
            row("clock/date/visible", "1", IssueCode.WRONG_TYPE, true) { it.clock.date.visible },
            row("clock/date/skeleton", "\"HHmm\"", IssueCode.INVALID_VALUE, "EEEEdMMMM") { it.clock.date.skeleton },
            row("clock/date/sizeSp", "8", IssueCode.CLAMPED, 12) { it.clock.date.sizeSp },
            // §4.8 favorites
            row("favorites/textScale", "3", IssueCode.CLAMPED, 2.0) { it.favorites.textScale },
            row("favorites/alignment", "1", IssueCode.WRONG_TYPE, Alignment.START) { it.favorites.alignment },
            row("favorites/verticalPosition", "\"left\"", IssueCode.UNKNOWN_ENUM, VerticalPosition.BOTTOM) {
                it.favorites.verticalPosition
            },
            row("favorites/showIcons", "1", IssueCode.WRONG_TYPE, false) { it.favorites.showIcons },
            row("favorites/showLabels", "1", IssueCode.WRONG_TYPE, true) { it.favorites.showLabels },
            row("favorites/spacingDp", "40", IssueCode.CLAMPED, 32) { it.favorites.spacingDp },
            // §4.9 search
            row("search/position", "\"middle\"", IssueCode.UNKNOWN_ENUM, SearchPosition.BOTTOM) { it.search.position },
            row("search/style", "\"glass\"", IssueCode.UNKNOWN_ENUM, SearchStyle.PILL) { it.search.style },
            row("search/cornerRadiusDp", "30", IssueCode.CLAMPED, 28) { it.search.cornerRadiusDp },
            row("search/showHint", "1", IssueCode.WRONG_TYPE, true) { it.search.showHint },
            // §4.10 alphabetIndex
            row("alphabetIndex/visibility", "\"sometimes\"", IssueCode.UNKNOWN_ENUM, IndexVisibility.ALWAYS) {
                it.alphabetIndex.visibility
            },
            row("alphabetIndex/side", "\"top\"", IssueCode.UNKNOWN_ENUM, IndexSide.END) { it.alphabetIndex.side },
            row(
                "alphabetIndex/glyphs",
                "\"stars\"",
                IssueCode.UNKNOWN_ENUM,
                IndexGlyphs.LETTERS,
            ) { it.alphabetIndex.glyphs },
            row("alphabetIndex/textSizeSp", "20", IssueCode.CLAMPED, 18) { it.alphabetIndex.textSizeSp },
            row("alphabetIndex/showBubble", "1", IssueCode.WRONG_TYPE, true) { it.alphabetIndex.showBubble },
            // §4.11 animation, §4.12 haptics
            row("animation/durationScale", "3", IssueCode.CLAMPED, 2.0) { it.animation.durationScale },
            row(
                "animation/listEntrance",
                "\"bounce\"",
                IssueCode.UNKNOWN_ENUM,
                ListEntrance.FADE,
            ) { it.animation.listEntrance },
            row("haptics/mode", "\"strong\"", IssueCode.UNKNOWN_ENUM, HapticsMode.FOLLOW_SYSTEM) { it.haptics.mode },
        )

    /** Base fixture with [path] set to the raw JSON [value] (intermediate objects created). */
    private fun fixture(
        path: String,
        value: String,
    ): ByteArray {
        val v = (JsonParser.parseText(value) as app.strata.launcher.core.common.Outcome.Ok).value
        val root = json(minimal())
        return JsonWriter.prettyBytes(set(root, path.split('/'), v))
    }

    private fun set(
        obj: JsonObject,
        keys: List<String>,
        v: JsonValue,
    ): JsonObject {
        if (keys.size == 1) return obj.with(keys[0], v)
        val child = obj[keys[0]] as? JsonObject ?: JsonObject.EMPTY
        return obj.with(keys[0], set(child, keys.drop(1), v))
    }

    private fun get(
        obj: JsonObject,
        keys: List<String>,
    ): JsonValue? = if (keys.size == 1) obj[keys[0]] else (obj[keys[0]] as? JsonObject)?.let { get(it, keys.drop(1)) }

    @Test
    fun `T-THEME-20 every policy row yields its documented issue and value`() {
        val groupsCovered = HashSet<String>()
        for (r in rows) {
            // Fresh id generator per fixture, so a generated id is always ...0001.
            val parsed =
                (
                    app.strata.launcher.core.theme.parse
                        .ThemeParser(
                            newId = fixedIds(),
                        ).parse(fixture(r.path, r.value)) as app.strata.launcher.core.common.Outcome.Ok
                ).value
            val what = "${r.path} = ${r.value}"
            if (r.code == null) {
                assertWithMessage(what).that(parsed.issues).isEmpty()
            } else {
                val issue = parsed.issues.firstOrNull { it.code == r.code }
                assertWithMessage("$what issues ${parsed.issues}").that(issue).isNotNull()
                assertWithMessage(what).that(issue!!.path).startsWith("/" + r.path.substringBefore('/'))
                assertWithMessage(what).that(issue.severity).isEqualTo(r.code.severity)
            }
            assertWithMessage(what).that(r.get(parsed.document)).isEqualTo(r.expected)
            // D and C keep the original value in the stored tree (§4 legend, §8.3).
            if (r.code?.policy == Policy.DEFAULT || r.code?.policy == Policy.CLAMP || r.code == IssueCode.UNKNOWN_KEY) {
                val original = (JsonParser.parseText(r.value) as app.strata.launcher.core.common.Outcome.Ok).value
                assertWithMessage("$what preserved").that(get(parsed.tree, r.path.split('/'))).isEqualTo(original)
            }
            groupsCovered += r.path.substringBefore('/')
        }
        assertThat(groupsCovered).containsAtLeast(
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
    }

    @Test
    fun `T-THEME-20 R rows reject the file`() {
        assertThat(parseErr("""{"id":"$MIN_ID","name":"T"}""")).isInstanceOf(ThemeError.NotATheme::class.java)
        assertThat(
            parseErr("""{"schemaVersion":"1","id":"$MIN_ID","name":"T"}"""),
        ).isInstanceOf(ThemeError.NotATheme::class.java)
        assertThat(
            parseErr("""{"schemaVersion":1.5,"id":"$MIN_ID","name":"T"}"""),
        ).isInstanceOf(ThemeError.NotATheme::class.java)
        assertThat(
            parseErr("""{"schemaVersion":0,"id":"$MIN_ID","name":"T"}"""),
        ).isInstanceOf(ThemeError.NotATheme::class.java)
        assertThat(parseErr("""[1]""")).isInstanceOf(ThemeError.NotATheme::class.java)
        val newer = parseErr("""{"schemaVersion":2,"id":"$MIN_ID","name":"T"}""")
        assertThat(newer).isInstanceOf(ThemeError.NewerVersion::class.java)
        assertThat((newer as ThemeError.NewerVersion).schemaVersion).isEqualTo(2)
        assertThat(newer.issue.policy).isEqualTo(Policy.REJECT)
        assertThat(parseOk("""{"schemaVersion":1.0,"id":"$MIN_ID","name":"T"}""").issues).isEmpty()
    }

    @Test
    fun `T-THEME-20 name fixtures - graphemes, not code points`() {
        // 64 thumbs-up emoji with a skin-tone modifier: 64 graphemes, 128 code points, 256 UTF-16 units.
        val emoji = "\uD83D\uDC4D\uD83C\uDFFD".repeat(64)
        assertThat(emoji.codePointCount(0, emoji.length)).isEqualTo(128)
        val ok = parseOk(minimal().replace("\"name\":\"T\"", "\"name\":\"$emoji\""))
        assertThat(ok.issues).isEmpty()
        assertThat(ok.document.name).isEqualTo(emoji)

        // 65 graphemes of combining text → truncated to 64, and the tree carries the fix.
        val combining = "e\u0301\u0323".repeat(65)
        val t = parseOk(minimal().replace("\"name\":\"T\"", "\"name\":\"$combining\""))
        assertThat(t.issues.map { it.code }).containsExactly(IssueCode.TRUNCATED)
        assertThat(t.issues.single().policy).isEqualTo(Policy.TRUNCATE)
        assertThat(t.document.name).isEqualTo("e\u0301\u0323".repeat(64))
        assertThat(Graphemes.count(t.document.name)).isEqualTo(64)
        assertThat((t.tree["name"] as app.strata.launcher.core.json.JsonString).value).isEqualTo(t.document.name)

        // U+0007 in a name is removed with a `fixed` issue.
        val bell = parseOk(minimal().replace("\"name\":\"T\"", "\"name\":\"Be\\u0007ll\""))
        assertThat(bell.document.name).isEqualTo("Bell")
        assertThat(bell.issues.single().code).isEqualTo(IssueCode.CONTROL_CHARS_REMOVED)
        assertThat(bell.issues.single().severity).isEqualTo(Severity.FIXED)
    }

    @Test
    fun graphemeSegmentation() {
        assertThat(Graphemes.count("")).isEqualTo(0)
        assertThat(Graphemes.count("abc")).isEqualTo(3)
        assertThat(Graphemes.count("\r\n")).isEqualTo(1)
        assertThat(Graphemes.count("\uD83C\uDDE9\uD83C\uDDEA\uD83C\uDDEB\uD83C\uDDF7")).isEqualTo(2) // DE FR flags
        assertThat(Graphemes.count("\uD83D\uDC69\u200D\uD83D\uDCBB")).isEqualTo(1) // woman technologist (ZWJ)
        assertThat(Graphemes.count("\u1100\u1161\u11A8")).isEqualTo(1) // Hangul L V T
        assertThat(Graphemes.count("\uAC01\uAC00")).isEqualTo(2)
        assertThat(Graphemes.count("\u2764\uFE0F")).isEqualTo(1)
    }

    @Test
    fun `issues never echo more than 64 characters of file content`() {
        val longKey = "k".repeat(4000)
        val p = parseOk(minimal("\"$longKey\":1"))
        assertThat(p.issues.single().code).isEqualTo(IssueCode.UNKNOWN_KEY)
        assertThat(
            p.issues
                .single()
                .message
                .count { it == 'k' },
        ).isAtMost(64)
        assertThat(
            p.issues
                .single()
                .path
                .count { it == 'k' },
        ).isAtMost(64)
    }
}
