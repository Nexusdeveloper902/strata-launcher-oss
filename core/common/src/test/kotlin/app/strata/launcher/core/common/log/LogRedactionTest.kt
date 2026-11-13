package app.strata.launcher.core.common.log

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

/** T-PRIV-06 (U half), ARCHITECTURE §11: package names hashed, exception messages dropped, own classes kept. */
class LogRedactionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun hashIsSixHexAndStable() {
        val h = LogRedaction.hash("com.example.mail")
        assertThat(h).matches("[0-9a-f]{6}")
        assertThat(LogRedaction.hash("com.example.mail")).isEqualTo(h)
        assertThat(LogRedaction.hash("com.example.maps")).isNotEqualTo(h)
    }

    @Test
    fun packagesAndComponentsAreHashed_platformAndOwnNamesKept() {
        val out =
            LogRedaction.message(
                "icon failed for ComponentInfo{com.example.mail/com.example.mail.Inbox} in strata.db via " +
                    "app.strata.launcher.home.HomeActivity and android.app.Activity (AppRow.kt)",
            )
        assertThat(out).doesNotContain("com.example")
        assertThat(out).contains("<id:${LogRedaction.hash("com.example.mail/com.example.mail.Inbox")}>")
        assertThat(out).contains("strata.db")
        assertThat(out).contains("app.strata.launcher.home.HomeActivity")
        assertThat(out).contains("android.app.Activity")
        assertThat(out).contains("AppRow.kt")
    }

    @Test
    fun aPlatformOwnerWithAForeignClassIsHashed() {
        val out = LogRedaction.message("start android/com.example.secret.Main")
        assertThat(out).doesNotContain("secret")
    }

    @Test
    fun redactedThrowableHasNoMessageButKeepsClassFramesAndCauses() {
        val cause = IllegalStateException("user 10 com.example.secret")
        val t = SecurityException("Permission Denial: com.example.secret/.Main", cause)
        val r = LogRedaction.throwable(t)
        val text = RotatingFileLogSink.format(LogEntry(0, LogLevel.WARN, "T", "x", r))
        assertThat(text).doesNotContain("secret")
        assertThat(text).doesNotContain("Permission Denial")
        assertThat(text).contains("java.lang.SecurityException")
        assertThat(text).contains("Caused by: java.lang.IllegalStateException")
        assertThat(text).contains("LogRedactionTest")
    }

    @Test
    fun ringLoggerRedactsBeforeAnySink() {
        val seen = mutableListOf<LogEntry>()
        val logger = RingLogger(sinks = listOf(LogSink { seen += it }), redact = true)
        logger.w("T", "launch of com.example.secret failed", RuntimeException("com.example.secret"))
        val entry = seen.single()
        assertThat(entry.message).doesNotContain("com.example")
        assertThat(entry.throwable!!.message).isNull()
        assertThat(entry.throwable.toString()).isEqualTo("java.lang.RuntimeException")
        assertThat(logger.snapshot().single().message).isEqualTo(entry.message)
    }

    @Test
    fun ringLoggerWithoutRedactKeepsTheMessage() {
        val logger = RingLogger()
        logger.d("T", "com.example.debug")
        assertThat(logger.snapshot().single().message).isEqualTo("com.example.debug")
    }

    @Test
    fun exportLineReducesUnknownLinesToTheClassName() {
        assertThat(LogRedaction.exportLine("java.lang.SecurityException: uid 10123 com.example.secret"))
            .isEqualTo("java.lang.SecurityException: ${LogRedaction.REMOVED}")
        assertThat(LogRedaction.exportLine("Caused by: java.io.IOException: /data/user/0/com.example.x/file"))
            .isEqualTo("Caused by: java.io.IOException: ${LogRedaction.REMOVED}")
        assertThat(LogRedaction.exportLine("Mail Inbox query text")).isEqualTo(LogRedaction.REMOVED)
        assertThat(LogRedaction.exportLine("\tat com.example.Lib.run(Lib.java:3)")).doesNotContain("com.example")
        assertThat(LogRedaction.exportLine("\tat app.strata.launcher.A.b(A.kt:3)"))
            .isEqualTo("\tat app.strata.launcher.A.b(A.kt:3)")
        assertThat(LogRedaction.exportLine("2026-01-01T00:00:00.000Z W/Icons: icon failed for com.example.mail"))
            .isEqualTo(
                "2026-01-01T00:00:00.000Z W/Icons: icon failed for <id:${LogRedaction.hash("com.example.mail")}>",
            )
    }

    /** A debug-build file (raw names and exception messages) still exports clean, oldest file first. */
    @Test
    fun diagnosticLogOfADebugFileIsClean() {
        val dir = tmp.newFolder("logs")
        val direct = Executor { it.run() }
        val sink = RotatingFileLogSink(directory = { dir }, maxBytes = 300, executor = direct)
        val logger = RingLogger(sinks = listOf(sink))
        logger.i("Home", "first entry")
        repeat(
            5,
        ) { logger.w("Launch", "launch of com.example.secret$it failed", SecurityException("com.example.secret")) }
        logger.i("Home", "last entry")
        assertThat(File(dir, "strata-1.log").exists()).isTrue()
        val text = DiagnosticLog.build(dir, header = listOf("Strata diagnostic log", "Version 1.0"))
        assertThat(text).startsWith("Strata diagnostic log\nVersion 1.0\n\n")
        assertThat(text).doesNotContain("com.example")
        assertThat(text).contains("java.lang.SecurityException")
        assertThat(text.indexOf("last entry")).isGreaterThan(text.indexOf("W/Launch"))
    }

    @Test
    fun diagnosticLogKeepsTheNewestCharacters_andSaysWhenEmpty() {
        val dir = tmp.newFolder("logs")
        assertThat(DiagnosticLog.build(dir, header = listOf("h"))).contains(DiagnosticLog.EMPTY)
        File(dir, "strata-0.log").writeText((1..1000).joinToString("\n") { "2026-01-01T00:00:00.000Z I/T: line $it" })
        val text = DiagnosticLog.build(dir, header = listOf("h"), maxChars = 2_000)
        assertThat(text).contains("line 1000")
        assertThat(text).doesNotContain("line 1\n")
        assertThat(text.length).isLessThan(2_100)
    }
}
