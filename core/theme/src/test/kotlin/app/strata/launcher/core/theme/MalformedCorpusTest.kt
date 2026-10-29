package app.strata.launcher.core.theme

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.theme.parse.IssueCode
import app.strata.launcher.core.theme.parse.ThemeError
import app.strata.launcher.core.theme.parse.ThemeErrorText
import app.strata.launcher.testing.fixtures.MalformedThemeCorpus
import app.strata.launcher.testing.fixtures.MalformedThemeCorpus.Expect
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.ByteArrayInputStream

/** T-THEME-43: the malformed JSON corpus is rejected per THEME_SYSTEM §8.1 with the §8.2 messages. */
class MalformedCorpusTest {
    @Test
    fun `T-THEME-43 each corpus file is rejected with the right message, or accepted at the boundary`() {
        val entries = MalformedThemeCorpus.entries()
        assertThat(entries.map { it.name }).containsAtLeast(
            "256KiB+1", "depth17", "129keys", "257items", "2049values", "4097string", "33charNumber", "duplicateKey",
            "lineComment", "trailingComma", "NaN", "invalidUtf8", "bom",
        )
        for (e in entries) {
            // Both entry points: whole bytes and capped stream.
            for (result in listOf(parser.parse(e.bytes), parser.parse(ByteArrayInputStream(e.bytes)))) {
                when (e.expect) {
                    Expect.OK -> assertWithMessage(e.name).that(result).isInstanceOf(Outcome.Ok::class.java)
                    else -> {
                        val err = (result as? Outcome.Err)?.error
                        assertWithMessage(e.name).that(err).isNotNull()
                        val text = ThemeErrorText.english(err!!)
                        when (e.expect) {
                            Expect.TOO_LARGE -> {
                                assertThat(err).isInstanceOf(ThemeError.TooLarge::class.java)
                                assertThat(text).isEqualTo("The file is too large.")
                            }
                            Expect.ENCODING -> {
                                assertThat(err.issue.code).isEqualTo(IssueCode.INVALID_ENCODING)
                                assertThat(text).isEqualTo("The file is damaged.")
                            }
                            else -> {
                                assertWithMessage(e.name).that(err).isInstanceOf(ThemeError.Damaged::class.java)
                                val code =
                                    when (e.expect) {
                                        Expect.LIMIT -> IssueCode.LIMIT_EXCEEDED
                                        Expect.DUPLICATE_KEY -> IssueCode.DUPLICATE_KEY
                                        else -> IssueCode.MALFORMED_JSON
                                    }
                                assertWithMessage(e.name).that(err.issue.code).isEqualTo(code)
                                assertWithMessage(e.name).that(err.issue.line).isNotNull()
                                assertWithMessage(e.name).that(text).matches("The file is damaged \\(line \\d+, column \\d+\\)\\.")
                            }
                        }
                        // Never echo file content: the longest run of a repeated content char stays short.
                        assertWithMessage(e.name).that(err.issue.message.length + text.length).isLessThan(200)
                        assertWithMessage(e.name).that(err.issue.message).doesNotContain("sss")
                    }
                }
            }
        }
    }

    @Test
    fun `T-THEME-43 positions point at the offending token`() {
        val dup = MalformedThemeCorpus.entries().single { it.name == "duplicateKey" }
        val issue = (parser.parse(dup.bytes) as Outcome.Err).error.issue
        assertThat(issue.line to issue.column).isEqualTo(3 to 1)
        val comment = MalformedThemeCorpus.entries().single { it.name == "lineComment" }
        val c = (parser.parse(comment.bytes) as Outcome.Err).error.issue
        assertThat(c.line to c.column).isEqualTo(2 to 1)
    }

    @Test
    fun `the BOM is tolerated and dropped on write`() {
        val bom = MalformedThemeCorpus.entries().single { it.name == "bom" }
        val p = parseOk(bom.bytes)
        val written = app.strata.launcher.core.theme.write.ThemeWriter.write(p.tree)
        assertThat(written[0]).isEqualTo('{'.code.toByte())
    }
}
