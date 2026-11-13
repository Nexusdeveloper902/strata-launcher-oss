package app.strata.launcher.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.strata.launcher.core.theme.model.SearchStyle

/** Pill height (VISUAL_SPEC §2, §9.1). */
val SEARCH_PILL_HEIGHT = 52.dp

/** Shape of the search pill and of the search field (`search.style`, FR-THEME-14). */
@Composable
fun searchShape(): Shape =
    when (Strata.theme.search.style) {
        SearchStyle.PILL -> {
            RoundedCornerShape(percent = 50)
        }

        SearchStyle.BOX -> {
            RoundedCornerShape(
                Strata.theme.search.cornerRadiusDp
                    .coerceAtLeast(0)
                    .dp,
            )
        }

        SearchStyle.UNDERLINE -> {
            RoundedCornerShape(0.dp)
        }
    }

/**
 * The search pill shell (VISUAL_SPEC §9.1): `surface` fill, leading search glyph (20 dp,
 * `textSecondary`), then [content] (hint or field). With [onClick] the whole pill is one button.
 */
@Composable
fun SearchPillFrame(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = Strata.colors
    val underline = Strata.theme.search.style == SearchStyle.UNDERLINE
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                // Grows with large text (NFR-A11Y-02); 52 dp at the default size.
                .heightIn(min = SEARCH_PILL_HEIGHT)
                .background(if (underline) Color.Transparent else colors.surface, searchShape())
                .then(
                    if (onClick !=
                        null
                    ) {
                        Modifier.focusRing().clickable(role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                ).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SearchGlyph(colors.textSecondary)
        content()
    }
}

/** Magnifier glyph drawn in code (no icon asset; decorative). */
@Composable
fun SearchGlyph(color: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val stroke = 2.dp.toPx()
        val r = size.minDimension * 0.32f
        val c = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(color, radius = r, center = c, style = Stroke(stroke))
        val d = r * 0.72f
        drawLine(
            color,
            Offset(c.x + d, c.y + d),
            Offset(size.width * 0.9f, size.height * 0.9f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
