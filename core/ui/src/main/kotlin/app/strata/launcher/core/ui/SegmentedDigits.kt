package app.strata.launcher.core.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil

/**
 * Seven-segment digits for the `segmented` and `countdownBoard` clock styles (VISUAL_SPEC §9.4). Our own geometry: an
 * upright cell 0.56 × the digit height, bars 0.12 × the height with pointed ends, a small gap where bars meet. Every
 * cell also draws its unlit bars in a faint "ghost" colour, like a segment display that is switched on.
 *
 * A display string holds `0`–`9` (any Unicode decimal digit, mapped by its value), `:` (two square dots) and a space
 * (a blank digit cell: ghost bars only, used to pad a one-digit hour).
 */
object SevenSegment {
    /** Bar bits: a top, b upper right, c lower right, d bottom, e lower left, f upper left, g middle. */
    const val A = 1
    const val B = 2
    const val C = 4
    const val D = 8
    const val E = 16
    const val F = 32
    const val G = 64
    const val ALL = A or B or C or D or E or F or G

    private val DIGITS =
        intArrayOf(
            A or B or C or D or E or F, // 0
            B or C, // 1
            A or B or G or E or D, // 2
            A or B or G or C or D, // 3
            F or G or B or C, // 4
            A or F or G or C or D, // 5
            A or F or G or E or C or D, // 6
            A or B or C, // 7
            ALL, // 8
            A or B or C or D or F or G, // 9
        )

    /** Width of a digit cell, of a colon cell and of the gap between cells, as fractions of the digit height. */
    const val DIGIT_WIDTH = 0.56f
    const val COLON_WIDTH = 0.26f
    const val CELL_GAP = 0.16f
    const val BAR = 0.12f
    private const val JOINT_GAP = 0.015f

    /** The lit bars of [digit] (0–9). */
    fun mask(digit: Int): Int = DIGITS[digit]

    /**
     * [text] as display cells: a digit value 0–9, [BLANK] or [COLON]. Null when it holds anything else (the caller
     * then draws the time as plain text).
     */
    fun cells(text: String): List<Int>? =
        text.map { ch ->
            when {
                ch == ':' -> COLON
                ch == ' ' -> BLANK
                else -> Character.digit(ch, RADIX).takeIf { it >= 0 } ?: return null
            }
        }

    /** Natural width of [cells] for a digit [height] (same unit). */
    fun width(
        cells: List<Int>,
        height: Float,
    ): Float {
        if (cells.isEmpty()) return 0f
        val sum = cells.sumOf { (if (it == COLON) COLON_WIDTH else DIGIT_WIDTH).toDouble() }.toFloat()
        return height * (sum + CELL_GAP * (cells.size - 1))
    }

    /** Draws [cells] left to right from x = 0, each [height] tall. */
    fun draw(
        scope: DrawScope,
        cells: List<Int>,
        height: Float,
        lit: Color,
        ghost: Color,
    ) {
        var x = 0f
        for (cell in cells) {
            if (cell == COLON) {
                val w = COLON_WIDTH * height
                val t = BAR * height
                for (fy in COLON_DOTS) {
                    scope.drawRect(lit, Offset(x + (w - t) / 2f, fy * height - t / 2f), Size(t, t))
                }
                x += w
            } else {
                val on = if (cell == BLANK) 0 else mask(cell)
                for (bar in BARS) {
                    val path = bar(bar, x, height)
                    scope.drawPath(path, if (on and bar != 0) lit else ghost)
                }
                x += DIGIT_WIDTH * height
            }
            x += CELL_GAP * height
        }
    }

    /** The hexagonal bar [bit] of a digit cell at [x0], [h] tall. */
    private fun bar(
        bit: Int,
        x0: Float,
        h: Float,
    ): Path {
        val w = DIGIT_WIDTH * h
        val t = BAR * h
        val g = JOINT_GAP * h
        val half = t / 2f
        return when (bit) {
            A -> horizontal(x0, w, half, half, g)
            G -> horizontal(x0, w, h / 2f, half, g)
            D -> horizontal(x0, w, h - half, half, g)
            F -> vertical(x0 + half, half + g, h / 2f - g, half)
            B -> vertical(x0 + w - half, half + g, h / 2f - g, half)
            E -> vertical(x0 + half, h / 2f + g, h - half - g, half)
            else -> vertical(x0 + w - half, h / 2f + g, h - half - g, half) // C
        }
    }

    private fun horizontal(
        x0: Float,
        w: Float,
        yc: Float,
        half: Float,
        g: Float,
    ): Path {
        val l = x0 + half + g
        val r = x0 + w - half - g
        return Path().apply {
            moveTo(l, yc)
            lineTo(l + half, yc - half)
            lineTo(r - half, yc - half)
            lineTo(r, yc)
            lineTo(r - half, yc + half)
            lineTo(l + half, yc + half)
            close()
        }
    }

    private fun vertical(
        xc: Float,
        top: Float,
        bottom: Float,
        half: Float,
    ): Path =
        Path().apply {
            moveTo(xc, top)
            lineTo(xc + half, top + half)
            lineTo(xc + half, bottom - half)
            lineTo(xc, bottom)
            lineTo(xc - half, bottom - half)
            lineTo(xc - half, top + half)
            close()
        }

    /** The `countdownBoard` label before the digits ("T" and a minus sign); not spoken. */
    const val BOARD_PREFIX = "T\u2212"

    const val BLANK = -1
    const val COLON = -2
    private const val RADIX = 10
    private val BARS = intArrayOf(A, B, C, D, E, F, G)
    private val COLON_DOTS = floatArrayOf(0.32f, 0.68f)
}

/**
 * [cells] (from [SevenSegment.cells]) as seven-segment digits [height] tall, scaled down to the available width when
 * they do not fit (a large `sizeSp` on a narrow screen). Draws left to right in every layout direction: clock digits
 * are never mirrored (VISUAL_SPEC §8.5).
 */
@Composable
fun SegmentedDigits(
    cells: List<Int>,
    height: Dp,
    lit: Color,
    ghost: Color,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier =
            modifier
                .layout { measurable, constraints ->
                    val h = height.toPx()
                    val natural = SevenSegment.width(cells, h)
                    val scale =
                        if (constraints.hasBoundedWidth && natural > constraints.maxWidth && natural > 0f) {
                            constraints.maxWidth / natural
                        } else {
                            1f
                        }
                    val w = ceil(natural * scale).toInt()
                    val hh = ceil(h * scale).toInt()
                    val placeable = measurable.measure(Constraints.fixed(w, hh))
                    layout(w, hh) { placeable.place(0, 0) }
                }.drawBehind { SevenSegment.draw(this, cells, size.height, lit, ghost) },
    )
}
