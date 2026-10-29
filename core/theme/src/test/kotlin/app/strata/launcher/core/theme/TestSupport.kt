package app.strata.launcher.core.theme

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonParser
import app.strata.launcher.core.theme.parse.ParsedTheme
import app.strata.launcher.core.theme.parse.ThemeError
import app.strata.launcher.core.theme.parse.ThemeParser
import app.strata.launcher.testing.fixtures.ThemeFixtures
import java.io.File
import java.util.concurrent.TimeUnit

/** Deterministic id generator for tests. */
fun fixedIds(): () -> String {
    var n = 0
    return { "00000000-0000-4000-8000-%012d".format(++n) }
}

val parser = ThemeParser(newId = fixedIds())

fun parseOk(bytes: ByteArray): ParsedTheme =
    when (val r = parser.parse(bytes)) {
        is Outcome.Ok -> r.value
        is Outcome.Err -> error("expected a theme, got ${r.error}")
    }

fun parseOk(text: String): ParsedTheme = parseOk(text.toByteArray())

fun parseErr(bytes: ByteArray): ThemeError =
    when (val r = parser.parse(bytes)) {
        is Outcome.Ok -> error("expected rejection, got ${r.value.issues}")
        is Outcome.Err -> r.error
    }

fun parseErr(text: String): ThemeError = parseErr(text.toByteArray())

fun sample(name: String): ParsedTheme = parseOk(ThemeFixtures.sample("$name.strata-theme.json"))

fun json(text: String): JsonObject = (JsonParser.parse(text.toByteArray()) as Outcome.Ok).value as JsonObject

const val MIN_ID = "11111111-2222-4333-8444-555555555555"

/** `{schemaVersion:1,id,name}` plus [extra] members (raw JSON text without braces). */
fun minimal(extra: String = ""): String =
    """{"schemaVersion":1,"id":"$MIN_ID","name":"T"${if (extra.isEmpty()) "" else ",$extra"}}"""

/**
 * Validates [files] with `tools/check_themes.py`'s schema validator (Python + jsonschema), or returns
 * null when Python or jsonschema is not installed (the CI image has both, T-CI-09).
 */
fun pythonSchemaErrors(files: List<File>): String? {
    val root =
        generateSequence(
            File("").absoluteFile,
        ) { it.parentFile }.firstOrNull { File(it, "spec/theme.schema.json").isFile }
            ?: return null
    val script =
        """
        import json, sys
        from jsonschema import Draft202012Validator
        v = Draft202012Validator(json.load(open(sys.argv[1])))
        bad = []
        for f in sys.argv[2:]:
            errs = list(v.iter_errors(json.load(open(f))))
            if errs: bad.append(f + ": " + "; ".join(e.message for e in errs))
        print("\n".join(bad)); sys.exit(0)
        """.trimIndent()
    return try {
        val p =
            ProcessBuilder(
                listOf(
                    "python3",
                    "-I",
                    "-c",
                    script,
                    File(root, "spec/theme.schema.json").path,
                ) + files.map { it.path },
            ).redirectErrorStream(true)
                .start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) null else out.trim()
    } catch (e: java.io.IOException) {
        null
    }
}
