package app.strata.launcher.a11y

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import app.strata.launcher.feature.theme.EditorTestTags

/**
 * Text that does not fit (NFR-A11Y-02, NFR-I18N-01): a Text whose layout overflows its box or was ellipsized. The
 * layout result is the one the Text drew with (Compose semantics `GetTextLayoutResult`). [allowEllipsis] are tags of
 * texts that ellipsize by design (none in the fixtures used here: app labels are short).
 */
fun ComposeTestRule.clippedTexts(allowEllipsis: Set<String> = emptySet()): List<String> =
    textNodes().filterNot { it.hasAncestorTagged(MINIATURES) }.mapNotNull { n ->
        val layout = n.layoutResult() ?: return@mapNotNull null
        val tag = n.config.getOrNull(SemanticsProperties.TestTag)
        val ellipsized = (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
        // A line drawn wider than the text's box is cut off (single-line texts that do not wrap).
        val cut =
            (0 until layout.lineCount).any {
                layout.getLineRight(it) - layout.getLineLeft(it) >
                    layout.size.width + 1f
            }
        when {
            layout.didOverflowHeight -> "${describe(n)} (in ${n.taggedAncestor()}): cut off at the bottom"
            ellipsized && tag !in allowEllipsis -> "${describe(n)} (in ${n.taggedAncestor()}): ellipsized"
            else -> null
        }
    }

/** Pairs of texts that are drawn over each other (bounds intersect by more than [slackPx] in both directions). */
fun ComposeTestRule.overlappingTexts(slackPx: Float = 2f): List<String> {
    val nodes = textNodes().filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
    val out = mutableListOf<String>()
    for (i in nodes.indices) {
        for (j in i + 1 until nodes.size) {
            val a = nodes[i]
            val b = nodes[j]
            if (a.root != b.root || related(a, b)) continue
            val r: Rect = a.boundsInRoot.intersect(b.boundsInRoot)
            if (r.width > slackPx && r.height > slackPx) out += "${describe(a)}  ×  ${describe(b)}"
        }
    }
    return out
}

private fun related(
    a: SemanticsNode,
    b: SemanticsNode,
): Boolean {
    fun isAncestor(
        x: SemanticsNode,
        y: SemanticsNode,
    ): Boolean {
        var p = y.parent
        while (p != null) {
            if (p.id == x.id) return true
            p = p.parent
        }
        return false
    }
    return isAncestor(a, b) || isAncestor(b, a)
}

/**
 * Fixed-size miniatures of home (the theme editor's and the import summary's preview, THEME_SYSTEM §13): they show the
 * top of home at the chosen theme, so rows at the bottom edge are cut by design. Home itself is checked.
 */
private val MINIATURES = setOf(EditorTestTags.PREVIEW)

private fun SemanticsNode.taggedAncestor(): String? {
    var p = parent
    while (p != null) {
        p.config.getOrNull(SemanticsProperties.TestTag)?.let { return it }
        p = p.parent
    }
    return null
}

private fun SemanticsNode.hasAncestorTagged(tags: Set<String>): Boolean {
    var p: SemanticsNode? = this
    while (p != null) {
        if (p.config.getOrNull(SemanticsProperties.TestTag) in tags) return true
        p = p.parent
    }
    return false
}

private val SemanticsNode.root: Int
    get() {
        var n = this
        while (n.parent != null) n = n.parent!!
        return n.id
    }

private fun ComposeTestRule.textNodes(): List<SemanticsNode> =
    runCatching {
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
    }.getOrDefault(emptyList())
        .filterNot { it.config.contains(SemanticsProperties.HideFromAccessibility) }

private fun SemanticsNode.layoutResult(): TextLayoutResult? {
    val results = mutableListOf<TextLayoutResult>()
    val action = config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
    return if (action(results)) results.firstOrNull() else null
}
