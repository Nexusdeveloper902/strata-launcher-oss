package app.strata.launcher.a11y

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import app.strata.launcher.core.ui.AlphabetIndexTestTags
import app.strata.launcher.core.ui.FocusRingSemantics

/** One stop of a keyboard walk. */
data class FocusStop(
    val id: Int,
    val label: String,
    val ring: Boolean,
    val editable: Boolean,
) {
    val named: Boolean get() = !label.startsWith("#")
}

/** A readable name of [n]: test tag, content description or text. */
fun describe(n: SemanticsNode): String {
    val c = n.config
    val tag = c.getOrNull(SemanticsProperties.TestTag)
    val text = c.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
    val desc = c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
    val edit = c.getOrNull(SemanticsProperties.EditableText)?.text
    return listOfNotNull(tag, desc, text, edit).joinToString(" | ").ifEmpty { "#${n.id}" }
}

/** The spoken name only (content description, text or label of an editable field); tags are not spoken. */
fun spokenName(n: SemanticsNode): String? {
    val c = n.config
    val desc = c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
    val text = c.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
    val edit = c.getOrNull(SemanticsProperties.EditableText)?.text
    return listOfNotNull(desc, text, edit).joinToString(" ").takeIf { it.isNotBlank() }
}

/** The focused node now, or null (also when no Compose window is resumed: a query then throws, treated as none). */
fun ComposeTestRule.focused(): SemanticsNode? =
    runCatching {
        // Each window keeps its own focused node; an open dialog is the one that takes keys.
        onAllNodes(isFocused() and hasAnyAncestor(isDialog())).fetchSemanticsNodes().firstOrNull()
            ?: onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()
    }.getOrNull()

/** Presses [key] in the newest window (a dialog when one is open). */
fun ComposeTestRule.press(
    key: Key,
    times: Int = 1,
) {
    repeat(times) {
        // Keys go to the window of the node they are sent to: an open dialog takes them, as on a device.
        val dialogs = onAllNodes(isDialog())
        val target = if (dialogs.fetchSemanticsNodes().isNotEmpty()) dialogs[0] else onAllNodes(isRoot())[0]
        target.performKeyInput { pressKey(key) }
        waitForIdle()
    }
}

/**
 * Presses Tab until focus comes back to a stop already seen (or [max] presses) and returns the stops in order. A
 * stop's ring is the [FocusRingSemantics.Shown] flag on the focused node.
 */
fun ComposeTestRule.tabWalk(max: Int = 60): List<FocusStop> {
    val stops = mutableListOf<FocusStop>()
    enterOpenDialog()
    repeat(max) { step ->
        // In a dialog the first control is already focused; elsewhere the first Tab enters the screen.
        val inDialog = onAllNodes(isFocused() and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()
        if (step > 0 || !inDialog) press(Key.Tab)
        val n = focused() ?: return@repeat
        if (stops.any { it.id == n.id }) return stops
        stops +=
            FocusStop(
                id = n.id,
                label = describe(n),
                ring = n.config.getOrNull(FocusRingSemantics.Shown) == true,
                editable = n.config.contains(SemanticsActions.SetText),
            )
    }
    return stops
}

/**
 * Robolectric gives a dialog window no window focus, so keys never move focus into it; on a device the dialog takes
 * focus when it opens. Focus is put on the dialog's first focusable control instead, then the walk goes on with Tab.
 */
private fun ComposeTestRule.enterOpenDialog() {
    val dialogs = onAllNodes(isDialog()).fetchSemanticsNodes()
    if (dialogs.isEmpty()) return
    val first =
        onAllNodes(hasAnyAncestor(isDialog()) and SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus))
    if (first.fetchSemanticsNodes().isNotEmpty()) {
        first[0].performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
    }
}

/** A node something can be done with: click, long-click, toggle or a value to change. */
val isActionable: SemanticsMatcher =
    hasClickAction() or
        SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) or
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState) or
        SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)

/** Actionable nodes a screen reader can reach (a sheet scrim is hidden from accessibility on purpose). */
private fun ComposeTestRule.actionableNodes(): List<SemanticsNode> =
    onAllNodes(isActionable)
        .fetchSemanticsNodes()
        .filterNot { it.config.contains(SemanticsProperties.HideFromAccessibility) }

/**
 * NFR-A11Y-03 (T-A11Y-10): every actionable node is at least 48 × 48 dp (layout size, which Material's
 * `minimumInteractiveComponentSize` already enlarges). Index letters are rows of the 48 dp-wide scrub strip whose
 * pitch VISUAL_SPEC §9.3 fixes at 14–28 dp, so for them the width is checked. Returns the offenders.
 */
fun ComposeTestRule.touchTargetErrors(density: Density): List<String> {
    val min = with(density) { MIN_TARGET_DP * this.density }
    return actionableNodes().mapNotNull { n ->
        val tag = n.config.getOrNull(SemanticsProperties.TestTag)
        val letter = tag?.startsWith(AlphabetIndexTestTags.letter("")) == true
        // The touch bounds include Material's minimumInteractiveComponentSize padding (the effective target).
        val b = n.touchBoundsInRoot
        val w = maxOf(b.width, n.size.width.toFloat())
        val h = maxOf(b.height, n.size.height.toFloat())
        val ok = if (letter) w + 0.5f >= min else w + 0.5f >= min && h + 0.5f >= min
        if (ok) null else "${describe(n)}: ${w / density.density}×${h / density.density} dp"
    }
}

/** NFR-A11Y-01 (C half of T-A11Y-08): every actionable node has a spoken name. Returns the unnamed ones. */
fun ComposeTestRule.unnamedActionables(): List<String> =
    actionableNodes()
        .filter { spokenName(it) == null }
        .map { describe(it) }

const val MIN_TARGET_DP = 48f
