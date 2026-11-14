package app.strata.launcher.a11y

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** One text drawn below its WCAG minimum (NFR-A11Y-04, THEME_SYSTEM §7.1). */
data class ContrastFailure(
    val text: String,
    val where: String?,
    val ratio: Double,
    val minimum: Double,
    val foreground: Int,
    val background: Int,
) {
    override fun toString(): String =
        "\"$text\" (in $where): %.2f:1 < %.1f:1 (text #%08X on #%08X)".format(ratio, minimum, foreground, background)
}

/**
 * Pixel contrast of every text on screen (NFR-A11Y-04, WCAG 1.4.3). Each window (activity, dialog, popup) is drawn
 * into a bitmap with Robolectric's native graphics; for each text node the drawn line boxes are sampled: the most frequent colour
 * is the background, the pixel farthest from it in contrast is the text. A pixel that is not opaque (home draws over
 * the wallpaper, which a test has none of) is composited over black and over white and the worse ratio counts: the
 * theme's "AA on any wallpaper" rule (THEME_SYSTEM §7.3). Minimum 4.5:1, or 3:1 for large text (≥ 24 sp, or ≥ 18.66 sp
 * bold). Skipped: disabled controls (WCAG exempts inactive components), texts with no pixel that differs from the
 * background (not drawn: off screen or zero alpha), and texts outside [scope] when it is given.
 */
fun ComposeTestRule.contrastFailures(scope: Set<String> = emptySet()): List<ContrastFailure> {
    waitForIdle()
    val out = mutableListOf<ContrastFailure>()
    runOnIdle {
        val windows = windowRootViews().filter { it.isShown }
        for (window in windows) {
            if (window.width <= 0 || window.height <= 0 || !window.isShown) continue
            val owners = composeOwners(window)
            if (owners.isEmpty()) continue
            val image = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
            window.draw(Canvas(image))
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            val shot = Shot(pixels, image.width, image.height)
            for (owner in owners) {
                walk(owner.semanticsOwner.unmergedRootSemanticsNode) { n -> out += check(n, shot, scope) }
            }
        }
    }
    return out
}

private fun check(
    n: SemanticsNode,
    shot: Shot,
    scope: Set<String>,
): List<ContrastFailure> {
    if (scope.isNotEmpty() && !n.within(scope)) return emptyList()
    if (n.disabledOrHidden()) return emptyList()
    val layout = n.layoutResult() ?: return emptyList()
    val text =
        n.config
            .getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text }
            .orEmpty()
    if (text.isBlank()) return emptyList()
    val sample = shot.sample(n, layout) ?: return emptyList()
    val minimum = if (layout.isLarge()) LARGE_MINIMUM else TEXT_MINIMUM
    if (sample.ratio + EPSILON >= minimum) return emptyList()
    return listOf(ContrastFailure(text.take(MAX_TEXT), n.nearestTag(), sample.ratio, minimum, sample.fg, sample.bg))
}

/** The root view of every window (activities, dialogs, popups), as the framework's window manager holds them. */
@Suppress("UNCHECKED_CAST")
private fun windowRootViews(): List<View> {
    val global = Class.forName("android.view.WindowManagerGlobal")
    val instance = global.getMethod("getInstance").invoke(null)
    val field = global.getDeclaredField("mViews").apply { isAccessible = true }
    return (field.get(instance) as List<View>).toList()
}

/** The Compose roots inside [view]. */
private fun composeOwners(view: View): List<ViewRootForTest> {
    if (view is ViewRootForTest) return listOf(view)
    if (view !is ViewGroup) return emptyList()
    return (0 until view.childCount).flatMap { composeOwners(view.getChildAt(it)) }
}

private class Sample(
    val ratio: Double,
    val fg: Int,
    val bg: Int,
)

private class Shot(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
) {
    /** Pixels of the text's line boxes in this window, or null when it has none on screen. */
    fun sample(
        n: SemanticsNode,
        layout: TextLayoutResult,
    ): Sample? {
        val origin = n.positionInWindow
        val clip = n.boundsInWindow
        val counts = HashMap<Int, Int>()
        val region = ArrayList<Int>()
        for (line in 0 until layout.lineCount) {
            val l = (origin.x + layout.getLineLeft(line)).roundToInt()
            val r = (origin.x + layout.getLineRight(line)).roundToInt()
            val t = (origin.y + layout.getLineTop(line)).roundToInt()
            val b = (origin.y + layout.getLineBottom(line)).roundToInt()
            val x0 = max(max(min(l, r), clip.left.roundToInt()), 0)
            val x1 = min(min(max(l, r), clip.right.roundToInt()), width)
            val y0 = max(max(t, clip.top.roundToInt()), 0)
            val y1 = min(min(b, clip.bottom.roundToInt()), height)
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    val p = pixels[y * width + x]
                    region += p
                    counts[p] = (counts[p] ?: 0) + 1
                }
            }
        }
        if (region.isEmpty()) return null
        val bg = counts.maxBy { it.value }.key
        var best = 1.0
        var fg = bg
        for (p in region) {
            if (p == bg) continue
            val r = worstRatio(p, bg)
            if (r > best) {
                best = r
                fg = p
            }
        }
        // Nothing but the background: the text is not drawn here (another layer covers it, or it is transparent).
        if (fg == bg) return null
        return Sample(best, fg, bg)
    }
}

/** The ratio of two drawn pixels; translucent ones over a black and a white wallpaper, the worse of the two. */
internal fun worstRatio(
    a: Int,
    b: Int,
): Double {
    val opaque = alpha(a) == 255 && alpha(b) == 255
    if (opaque) return ratio(luminance(a), luminance(b))
    return min(
        ratio(luminance(over(a, BLACK)), luminance(over(b, BLACK))),
        ratio(luminance(over(a, WHITE)), luminance(over(b, WHITE))),
    )
}

private fun alpha(c: Int) = (c ushr 24) and 0xFF

/** Source-over in gamma-encoded sRGB (THEME_SYSTEM §7.2). */
private fun over(
    c: Int,
    under: Int,
): Int {
    val a = alpha(c) / 255.0

    fun ch(shift: Int) = (((c shr shift) and 0xFF) * a + ((under shr shift) and 0xFF) * (1 - a)).roundToInt()
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** WCAG 2.x relative luminance (0.04045 threshold). */
internal fun luminance(c: Int): Double {
    fun lin(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
}

internal fun ratio(
    l1: Double,
    l2: Double,
): Double = (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)

private fun walk(
    n: SemanticsNode,
    visit: (SemanticsNode) -> Unit,
) {
    visit(n)
    n.children.forEach { walk(it, visit) }
}

private fun SemanticsNode.nearestTag(): String? {
    var p: SemanticsNode? = this
    while (p != null) {
        p.config.getOrNull(SemanticsProperties.TestTag)?.let { return it }
        p = p.parent
    }
    return null
}

private fun SemanticsNode.within(tags: Set<String>): Boolean {
    var p: SemanticsNode? = this
    while (p != null) {
        if (p.config.getOrNull(SemanticsProperties.TestTag) in tags) return true
        p = p.parent
    }
    return false
}

/** A disabled control (or text inside one), or a node hidden from accessibility. */
private fun SemanticsNode.disabledOrHidden(): Boolean {
    var p: SemanticsNode? = this
    while (p != null) {
        if (p.config.contains(SemanticsProperties.Disabled)) return true
        if (p.config.contains(SemanticsProperties.HideFromAccessibility)) return true
        p = p.parent
    }
    return false
}

private fun SemanticsNode.layoutResult(): TextLayoutResult? {
    val action = config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
    val results = mutableListOf<TextLayoutResult>()
    return if (action(results)) results.firstOrNull() else null
}

/** WCAG large text: ≥ 18 pt (24 sp) or ≥ 14 pt (18.66 sp) bold. */
private fun TextLayoutResult.isLarge(): Boolean {
    val size: TextUnit = layoutInput.style.fontSize
    if (!size.isSpecified) return false
    val bold = (layoutInput.style.fontWeight ?: FontWeight.Normal) >= FontWeight.Bold
    return size.value >= LARGE_SP || (bold && size.value >= LARGE_BOLD_SP)
}

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
private const val TEXT_MINIMUM = 4.5
private const val LARGE_MINIMUM = 3.0
private const val LARGE_SP = 24f
private const val LARGE_BOLD_SP = 18.66f

/** Antialiasing and rounding of the sampled pixels. */
private const val EPSILON = 0.05
private const val MAX_TEXT = 40
