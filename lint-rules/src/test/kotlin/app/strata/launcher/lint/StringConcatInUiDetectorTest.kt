package app.strata.launcher.lint

import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import org.junit.Test

class StringConcatInUiDetectorTest {
    private val stubs =
        kotlin(
            """
        package androidx.compose.material3
        fun Text(text: String) {}
        """,
        ).indented()

    private fun check(body: String) =
        lint()
            .files(
                stubs,
                kotlin(
                    """
                import androidx.compose.material3.Text
                fun stringResource(id: Int): String = ""
                $body
                """,
                ).indented(),
            ).issues(StringConcatInUiDetector.ISSUE)
            .allowMissingSdk()
            .run()

    @Test
    fun concatenationPassedToText_isFlagged() {
        check("fun a(n: Int) { Text(\"Count: \" + n) }").expectErrorCount(1)
    }

    @Test
    fun templatePassedToText_isFlagged() {
        check("fun a(n: Int) { Text(\"Count: \$n\") }").expectErrorCount(1)
    }

    @Test
    fun resourceStringConcatenated_isFlagged() {
        check("fun a(n: String) { val s = stringResource(1) + \" \" + n }").expectErrorCount(1)
    }

    @Test
    fun plainLiteralsAndLogStrings_areClean() {
        check("fun a(n: Int) { val log = \"id=\" + n; Text(stringResource(1)) }").expectClean()
    }
}
