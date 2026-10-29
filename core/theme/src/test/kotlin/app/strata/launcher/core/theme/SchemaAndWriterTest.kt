package app.strata.launcher.core.theme

import app.strata.launcher.core.json.JsonParser
import app.strata.launcher.core.theme.parse.IssueCode
import app.strata.launcher.core.theme.parse.ThemeError
import app.strata.launcher.core.theme.parse.ThemeParser
import app.strata.launcher.core.theme.write.ThemeEncoder
import app.strata.launcher.core.theme.write.ThemeWriter
import app.strata.launcher.testing.fixtures.ThemeFixtures
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** T-CI-09 (schema conformance of samples, JVM half) and T-THEME-03 (writer output valid and formatted). */
class SchemaAndWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val zeroId = "00000000-0000-4000-8000-000000000000"

    @Test
    fun `T-CI-09 every sample is valid under our validator with no issue`() {
        for ((name, bytes) in ThemeFixtures.samples()) {
            val parsed = parseOk(bytes)
            assertWithMessage(name).that(parsed.issues).isEmpty()
            assertThat(parsed.isStrictlyValid).isTrue()
        }
    }

    @Test
    fun `T-CI-09 the six negative fixtures are rejected and the x- case accepted`() {
        // Same cases as tools/check_themes.py. Lenient mode keeps loading, so "rejected" = not strictly valid.
        assertThat(parseErr("""{"schemaVersion":2,"id":"$zeroId","name":"x"}""")).isInstanceOf(ThemeError.NewerVersion::class.java)
        val negatives =
            mapOf(
                """{"schemaVersion":1,"id":"nope","name":"x"}""" to IssueCode.GENERATED,
                """{"schemaVersion":1,"id":"$zeroId","name":"x","colors":{"light":{"accent":"#12345"}}}""" to IssueCode.INVALID_VALUE,
                """{"schemaVersion":1,"id":"$zeroId","name":"x","typography":{"scale":3}}""" to IssueCode.CLAMPED,
                """{"schemaVersion":1,"id":"$zeroId","name":"x","typography":{"family":{"kind":"user"}}}""" to IssueCode.INVALID_VALUE,
                """{"schemaVersion":1,"id":"$zeroId","name":"x","unknownKey":1}""" to IssueCode.UNKNOWN_KEY,
            )
        for ((text, code) in negatives) {
            val p = parseOk(text)
            assertWithMessage(text).that(p.isStrictlyValid).isFalse()
            assertWithMessage(text).that(p.issues.map { it.code }).contains(code)
        }
        val ext = parseOk("""{"schemaVersion":1,"id":"$zeroId","name":"x","x-vendor.thing":{"a":1},"icons":{"x-foo":true,"pack":null}}""")
        assertThat(ext.issues).isEmpty()
    }

    @Test
    fun `T-CI-09 samples also pass the python schema check when available`() {
        val files = ThemeFixtures.SAMPLE_NAMES.map { n -> File(tmp.root, n).apply { writeBytes(ThemeFixtures.sample(n)) } }
        val errors = pythonSchemaErrors(files)
        assumeTrue("python3 + jsonschema not installed", errors != null)
        assertThat(errors).isEmpty()
    }

    @Test
    fun `T-THEME-03 writer output is formatted and valid for every sample and Strata Default`() {
        val outputs = ArrayList<File>()
        val trees = ThemeFixtures.samples().mapValues { parseOk(it.value).tree } + ("strata-default.json" to StrataDefault.tree)
        for ((name, tree) in trees) {
            val text = ThemeWriter.writeString(tree)
            // Format: 2-space indent, LF only, exactly one trailing newline.
            assertWithMessage(name).that(text).doesNotContain("\r")
            assertWithMessage(name).that(text).endsWith("}\n")
            assertWithMessage(name).that(text).doesNotContain("\t")
            assertWithMessage(name).that(text.endsWith("\n\n")).isFalse()
            for (line in text.lines().filter { it.isNotEmpty() }) {
                val indent = line.length - line.trimStart(' ').length
                assertWithMessage("$name: $line").that(indent % 2).isEqualTo(0)
            }
            // Schema order of known top-level keys; unknown keys after them in original order.
            val keys = (JsonParser.parse(text.toByteArray()) as app.strata.launcher.core.common.Outcome.Ok).value.let { it as app.strata.launcher.core.json.JsonObject }.keys
            val known = keys.filter { it in ThemeParser.TOP }
            assertWithMessage(name).that(known).isInOrder(compareBy<String> { ThemeParser.TOP.indexOf(it) })
            assertWithMessage(name).that(keys.dropWhile { it in ThemeParser.TOP }.none { it in ThemeParser.TOP }).isTrue()
            // Valid under our validator, and semantically unchanged.
            val again = parseOk(text)
            assertWithMessage(name).that(again.issues).isEmpty()
            assertWithMessage(name).that(again.tree).isEqualTo(tree)
            outputs += File(tmp.root, "out-$name").apply { writeText(text) }
        }
        pythonSchemaErrors(outputs)?.let { assertWithMessage("python jsonschema").that(it).isEmpty() }
    }

    @Test
    fun `T-THEME-03 nested groups are written in schema order`() {
        val tree = json("""{"colors":{"dark":{"scrim":"#000000","background":"#111111"},"mode":"dark","x-a":1},"name":"N","id":"$zeroId","schemaVersion":1}""")
        assertThat(ThemeWriter.writeString(tree)).isEqualTo(
            """
            |{
            |  "schemaVersion": 1,
            |  "id": "$zeroId",
            |  "name": "N",
            |  "colors": {
            |    "mode": "dark",
            |    "dark": {
            |      "background": "#111111",
            |      "scrim": "#000000"
            |    },
            |    "x-a": 1
            |  }
            |}
            |
            """.trimMargin(),
        )
    }

    @Test
    fun `encoder output parses back to the same document`() {
        for ((name, bytes) in ThemeFixtures.samples()) {
            val doc = parseOk(bytes).document
            for (full in listOf(false, true)) {
                val again = parseOk(ThemeWriter.write(ThemeEncoder.encode(doc, full)))
                assertWithMessage("$name full=$full").that(again.issues).isEmpty()
                assertWithMessage("$name full=$full").that(again.document).isEqualTo(doc)
            }
        }
        assertThat(parseOk(ThemeWriter.write(StrataDefault.tree)).document).isEqualTo(StrataDefault.document)
    }
}
