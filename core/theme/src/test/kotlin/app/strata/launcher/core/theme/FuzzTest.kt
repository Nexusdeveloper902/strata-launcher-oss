package app.strata.launcher.core.theme

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.json.JsonArray
import app.strata.launcher.core.json.JsonBool
import app.strata.launcher.core.json.JsonNull
import app.strata.launcher.core.json.JsonNumber
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.json.JsonValue
import app.strata.launcher.core.json.JsonWriter
import app.strata.launcher.core.theme.parse.IssueCode
import app.strata.launcher.core.theme.parse.ParsedTheme
import app.strata.launcher.core.theme.parse.Policy
import app.strata.launcher.core.theme.parse.ThemeError
import app.strata.launcher.core.theme.parse.ThemeParser
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.ThemeResolver
import app.strata.launcher.core.theme.resolve.WallpaperColorsInput
import app.strata.launcher.testing.fixtures.ThemeFixtures
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.random.Random

/**
 * T-THEME-19: ≥ 10 000 mutations of the samples (type flips, out of range, unknown enums, truncation,
 * deletion). Every case: no exception from parse, validate or resolve, and the mutation yields its
 * documented policy (C/D/G/T/R) with an `Issue`.
 */
class FuzzTest {
    private sealed interface Seg

    private data class Key(val k: String) : Seg

    private data class Idx(val i: Int) : Seg

    private data class Leaf(val path: List<Seg>, val value: JsonValue) {
        val keys: List<String> get() = path.filterIsInstance<Key>().map { it.k }
        val pointer: String get() = path.joinToString("") { if (it is Key) "/${it.k}" else "/${(it as Idx).i}" }
        val last: String get() = keys.last()
    }

    private fun leaves(
        v: JsonValue,
        path: List<Seg> = emptyList(),
        out: MutableList<Leaf> = ArrayList(),
    ): List<Leaf> {
        when (v) {
            is JsonObject -> for ((k, c) in v.entries) leaves(c, path + Key(k), out)
            is JsonArray -> if (v.isEmpty()) out += Leaf(path, v) else v.forEachIndexed { i, c -> leaves(c, path + Idx(i), out) }
            else -> out += Leaf(path, v)
        }
        return out
    }

    /** All members (any depth) as paths, for deletions. */
    private fun members(
        v: JsonValue,
        path: List<Seg> = emptyList(),
        out: MutableList<List<Seg>> = ArrayList(),
    ): List<List<Seg>> {
        if (v is JsonObject) for ((k, c) in v.entries) {
            out += path + Key(k)
            members(c, path + Key(k), out)
        }
        return out
    }

    private fun set(
        v: JsonValue,
        path: List<Seg>,
        nv: JsonValue?,
    ): JsonValue {
        val head = path.first()
        val rest = path.drop(1)
        return when (head) {
            is Key -> {
                val o = v as JsonObject
                if (rest.isEmpty()) {
                    if (nv == null) o.without(head.k) else o.with(head.k, nv)
                } else {
                    o.with(head.k, set(o[head.k]!!, rest, nv))
                }
            }
            is Idx -> {
                val a = (v as JsonArray).toMutableList()
                a[head.i] = if (rest.isEmpty()) nv!! else set(a[head.i], rest, nv)
                JsonArray(a)
            }
        }
    }

    private val enumKeys =
        setOf(
            "mode", "kind", "density", "shape", "tint", "style", "hourFormat", "alignment", "verticalPosition", "position",
            "visibility", "side", "glyphs", "listEntrance", "labelCase", "fallback", "mediaType",
        )

    /** Required members of sub-objects: deleting them invalidates that sub-object (D). */
    private val requiredInSubObject = setOf("kind", "sha256", "package")

    private fun isExtension(l: Leaf) = l.keys.any { ThemeParser.X_KEY.matches(it) }

    private val parserFor = ThemeParser(newId = { "00000000-0000-4000-8000-00000000abcd" })

    private fun parse(bytes: ByteArray): Outcome<ParsedTheme, ThemeError> = parserFor.parse(bytes)

    private fun resolveAll(p: ParsedTheme) {
        for (platform in PLATFORMS) {
            // The resolver itself, not the safe wrapper: a throw here is a bug.
            ThemeResolver.resolve(p.document, platform)
        }
    }

    private fun hasIssue(
        p: ParsedTheme,
        code: IssueCode,
        pointer: String,
    ): Boolean = p.issues.any { it.code == code && pointer.startsWith(it.path) }

    @Test
    fun `T-THEME-19 10 000 mutations never crash and each yields its documented policy`() {
        val rnd = Random(19)
        val samples = ThemeFixtures.samples().mapValues { json(String(it.value)) }
        val counts = HashMap<String, Int>()
        var cases = 0
        while (cases < 10_000) {
            val (name, tree) = samples.entries.elementAt(cases % samples.size)
            val kind = listOf("typeFlip", "outOfRange", "unknownEnum", "truncation", "deletion")[(cases / samples.size) % 5]
            val ok = mutate(kind, tree, rnd, "$name #$cases")
            if (ok) {
                counts.merge(kind, 1, Int::plus)
                cases++
            }
        }
        assertThat(counts.values.sum()).isAtLeast(10_000)
        assertThat(counts.keys).containsExactly("typeFlip", "outOfRange", "unknownEnum", "truncation", "deletion")
    }

    /** Applies one mutation of [kind]; false when the sample offers no target for it (case not counted). */
    private fun mutate(
        kind: String,
        tree: JsonObject,
        rnd: Random,
        label: String,
    ): Boolean {
        val all = leaves(tree).filter { !isExtension(it) }
        when (kind) {
            "typeFlip" -> {
                val l = all.random(rnd)
                val flipped = flip(l.value, rnd, allowNull = l.keys != listOf("icons", "pack"))
                val r = parse(JsonWriter.prettyBytes(set(tree, l.path, flipped)))
                val what = "$label typeFlip ${l.pointer} -> $flipped"
                when {
                    l.keys == listOf("schemaVersion") -> assertWithMessage(what).that((r as Outcome.Err).error).isInstanceOf(ThemeError.NotATheme::class.java)
                    else -> {
                        val p = (r as Outcome.Ok).value
                        val expected =
                            when {
                                l.keys == listOf("id") || l.keys == listOf("name") -> IssueCode.GENERATED
                                l.keys == listOf("schemaMinor") && flipped is JsonNumber -> IssueCode.INVALID_VALUE
                                l.path.last() is Idx -> IssueCode.ITEM_DROPPED
                                else -> IssueCode.WRONG_TYPE
                            }
                        assertWithMessage("$what ${p.issues}").that(hasIssue(p, expected, l.pointer)).isTrue()
                        assertWithMessage(what).that(p.issues.first { it.code == expected }.policy)
                            .isEqualTo(if (expected == IssueCode.ITEM_DROPPED || expected == IssueCode.WRONG_TYPE || expected == IssueCode.INVALID_VALUE) Policy.DEFAULT else Policy.GENERATE)
                        resolveAll(p)
                    }
                }
            }
            "outOfRange" -> {
                val l = all.filter { it.value is JsonNumber }.randomOrNull(rnd) ?: return false
                val high = rnd.nextBoolean()
                val v = JsonNumber.parse(if (high) "1e6" else "-1e6")
                val r = parse(JsonWriter.prettyBytes(set(tree, l.path, v)))
                val what = "$label outOfRange ${l.pointer} -> $v"
                when (l.keys) {
                    listOf("schemaVersion") ->
                        assertWithMessage(what).that((r as Outcome.Err).error)
                            .isInstanceOf(if (high) ThemeError.NewerVersion::class.java else ThemeError.NotATheme::class.java)
                    listOf("schemaMinor") -> assertWithMessage(what).that(hasIssue((r as Outcome.Ok).value, IssueCode.INVALID_VALUE, l.pointer)).isTrue()
                    else -> {
                        val p = (r as Outcome.Ok).value
                        assertWithMessage("$what ${p.issues}").that(hasIssue(p, IssueCode.CLAMPED, l.pointer)).isTrue()
                        assertWithMessage(what).that(p.issues.first { it.code == IssueCode.CLAMPED }.policy).isEqualTo(Policy.CLAMP)
                        resolveAll(p)
                    }
                }
            }
            "unknownEnum" -> {
                val l = all.filter { it.value is JsonString && it.last in enumKeys }.randomOrNull(rnd) ?: return false
                val p = (parse(JsonWriter.prettyBytes(set(tree, l.path, JsonString("future-${rnd.nextInt(1000)}")))) as Outcome.Ok).value
                val what = "$label unknownEnum ${l.pointer}"
                assertWithMessage("$what ${p.issues}").that(hasIssue(p, IssueCode.UNKNOWN_ENUM, l.pointer)).isTrue()
                // D, original preserved verbatim.
                assertThat(p.issues.first { it.code == IssueCode.UNKNOWN_ENUM }.policy).isEqualTo(Policy.DEFAULT)
                resolveAll(p)
            }
            "truncation" -> {
                val bytes = JsonWriter.prettyBytes(tree)
                val cut = rnd.nextInt(0, bytes.size - 1) // always loses the closing brace
                when (val r = parse(bytes.copyOf(cut))) {
                    is Outcome.Ok -> error("$label truncated at $cut parsed")
                    is Outcome.Err -> {
                        assertWithMessage("$label truncation").that(r.error).isInstanceOf(ThemeError.Damaged::class.java)
                        assertThat(r.error.issue.policy).isEqualTo(Policy.REJECT)
                    }
                }
            }
            "deletion" -> {
                val m = members(tree).random(rnd)
                val keys = m.filterIsInstance<Key>().map { it.k }
                val r = parse(JsonWriter.prettyBytes(set(tree, m, null)))
                val what = "$label deletion $keys"
                when {
                    keys == listOf("schemaVersion") -> assertWithMessage(what).that((r as Outcome.Err).error).isInstanceOf(ThemeError.NotATheme::class.java)
                    else -> {
                        val p = (r as Outcome.Ok).value
                        when {
                            keys == listOf("id") || keys == listOf("name") ->
                                assertWithMessage(what).that(p.issues.map { it.code }).containsExactly(IssueCode.GENERATED)
                            keys.size > 1 && keys.last() in requiredInSubObject && keys[keys.size - 2] in setOf("family", "clockFamily", "image", "pack") ->
                                assertWithMessage(what).that(p.issues.map { it.code }).containsExactly(IssueCode.INVALID_VALUE)
                            keys.size > 1 && keys.last() == "id" && keys[keys.size - 2] in setOf("family", "clockFamily") ->
                                assertWithMessage(what).that(p.issues.map { it.code }).contains(IssueCode.INVALID_VALUE)
                            else -> assertWithMessage(what).that(p.issues).isEmpty() // field takes its default (D)
                        }
                        resolveAll(p)
                    }
                }
            }
        }
        return true
    }

    private fun flip(
        v: JsonValue,
        rnd: Random,
        allowNull: Boolean,
    ): JsonValue {
        val options =
            listOfNotNull(
                JsonString("flipped").takeIf { v !is JsonString },
                JsonNumber.of(rnd.nextInt(-5, 5)).takeIf { v !is JsonNumber },
                JsonBool.of(rnd.nextBoolean()).takeIf { v !is JsonBool },
                JsonArray(listOf(JsonNumber.of(1))).takeIf { v !is JsonArray },
                JsonObject("a" to JsonNumber.of(1)).takeIf { v !is JsonObject },
                JsonNull.takeIf { v !is JsonNull && allowNull },
            )
        return options.random(rnd)
    }

    private companion object {
        val PLATFORMS =
            listOf(
                PlatformInputs(apiLevel = 26),
                PlatformInputs(apiLevel = 30, systemDark = true, wallpaperColors = WallpaperColorsInput(0xFF3366AA.toInt(), 0xFFAA6633.toInt())),
            )
    }
}
