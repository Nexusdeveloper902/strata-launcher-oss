package app.strata.launcher.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UPolyadicExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UastBinaryOperator
import org.jetbrains.uast.skipParenthesizedExprUp

/**
 * NFR-I18N-01 / T-CI-04: user-visible text must not be assembled by concatenating or interpolating strings,
 * because word order and plurals differ per language. Flags a `+` chain or a string template that
 * (a) contains a call to `getString` / `stringResource` / `pluralStringResource` / `getQuantityString`, or
 * (b) is passed straight to a text-showing call (`Text`, `setText`, `Toast.makeText`, `setContentDescription`...).
 */
class StringConcatInUiDetector : Detector(), SourceCodeScanner {
    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(
            UPolyadicExpression::class.java,
            UBinaryExpression::class.java,
        )

    override fun createUastHandler(context: JavaContext) =
        object : com.android.tools.lint.client.api.UElementHandler() {
            override fun visitPolyadicExpression(node: UPolyadicExpression) = check(node)

            override fun visitBinaryExpression(node: UBinaryExpression) = check(node)

            private fun check(node: UPolyadicExpression) {
                if (node.operator != UastBinaryOperator.PLUS) return
                if (node.operands.size < 2) return
                // Only the outermost chain; inner `a + b` of `a + b + c` is the same polyadic node in UAST.
                if (skipParenthesizedExprUp(node.uastParent) is UPolyadicExpression) return
                val operands = flatten(node)
                if (operands.none { isStringy(it) }) return
                if (usesResourceString(operands) || isUiSinkArgument(node)) {
                    context.report(
                        ISSUE,
                        node,
                        context.getLocation(node),
                        "Do not build UI text with `+` or string templates; use a string resource with " +
                            "placeholders (`%1\$s`) or a plurals resource",
                    )
                }
            }
        }

    private fun isStringy(e: UExpression): Boolean =
        e.getExpressionType()?.canonicalText == "java.lang.String" || e.sourcePsi?.text?.startsWith("\"") == true

    private fun flatten(node: UPolyadicExpression): List<UExpression> =
        node.operands.flatMap { op ->
            val inner = skipParens(op)
            if (inner is UPolyadicExpression &&
                inner.operator == UastBinaryOperator.PLUS
            ) {
                flatten(inner)
            } else {
                listOf(inner)
            }
        }

    private fun skipParens(e: UExpression): UExpression =
        if (e is UParenthesizedExpression) skipParens(e.expression) else e

    private fun usesResourceString(operands: List<UExpression>): Boolean =
        operands.any { op ->
            val call = (op as? UCallExpression) ?: (op as? UQualifiedReferenceExpression)?.selector as? UCallExpression
            call?.methodName in RESOURCE_STRING_CALLS
        }

    private fun isUiSinkArgument(node: UExpression): Boolean {
        val call = skipParenthesizedExprUp(node.uastParent) as? UCallExpression ?: return false
        return call.methodName in UI_SINKS
    }

    companion object {
        private val RESOURCE_STRING_CALLS =
            setOf("getString", "stringResource", "pluralStringResource", "getQuantityString")
        private val UI_SINKS =
            setOf(
                "Text",
                "setText",
                "makeText",
                "setContentDescription",
                "setTitle",
                "setMessage",
                "setHint",
                "setError",
            )

        val ISSUE: Issue =
            Issue.create(
                id = "StringConcatInUi",
                briefDescription = "Concatenated user-visible text",
                explanation =
                    "Sentences assembled with `+` or string templates cannot be translated correctly " +
                        "(word order, plurals, RTL). Put the whole sentence in a string resource with placeholders, " +
                        "and use a plurals resource for counts.",
                category = Category.I18N,
                priority = 8,
                severity = Severity.ERROR,
                implementation = Implementation(StringConcatInUiDetector::class.java, Scope.JAVA_FILE_SCOPE),
            )
    }
}
