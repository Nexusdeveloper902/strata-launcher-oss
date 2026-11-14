package app.strata.launcher.core.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * [this] style, with only the growth that comes from a font scale above 1 limited so the glyph stays within [maxDp]
 * (fixed-size boxes: index letters, monogram tiles; NFR-A11Y-02). The theme's size at font scale 1 is never reduced,
 * even when it is larger than [maxDp], and nothing changes at scale ≤ 1. The theme's line height is scaled by the same
 * factor as the size, so its proportion is kept. Sizes are converted through [density], which applies the platform's
 * (nonlinear on API 34+) font scaling.
 */
fun TextStyle.capFontScaleGrowth(
    density: Density,
    maxDp: Dp,
): TextStyle {
    if (density.fontScale <= 1f || !fontSize.isSp) return this
    val themeDp = fontSize.value // at font scale 1, 1 sp = 1 dp
    val scaledDp = with(density) { fontSize.toDp().value }
    if (scaledDp <= maxDp.value || scaledDp <= themeDp) return this
    val targetDp = maxOf(themeDp, maxDp.value)
    val size = with(density) { targetDp.dp.toSp() }
    val ratio = size.value / fontSize.value
    return copy(fontSize = size, lineHeight = if (lineHeight.isSp) (lineHeight.value * ratio).sp else lineHeight)
}
