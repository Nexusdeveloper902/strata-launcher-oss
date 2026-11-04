package app.strata.launcher.feature.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.strata.launcher.core.theme.contrast.ContrastPair
import app.strata.launcher.core.theme.model.ClockStyle
import app.strata.launcher.core.theme.model.IndexSide
import app.strata.launcher.core.theme.model.IndexVisibility
import app.strata.launcher.core.theme.model.SearchPosition
import app.strata.launcher.core.theme.model.VerticalPosition
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import app.strata.launcher.core.ui.AppRow
import app.strata.launcher.core.ui.SearchPillFrame
import app.strata.launcher.core.ui.Strata
import app.strata.launcher.core.ui.StrataTheme
import app.strata.launcher.core.ui.apply
import app.strata.launcher.core.ui.strataBackdrop
import app.strata.launcher.core.theme.model.Alignment as ThemeAlignment

/** Where each §7.1 pair shows in the preview (the "show contrast problems" overlay and "Show me"). */
private enum class Spot { LIST, SEARCH, PRESSED, ACCENT }

private fun spotOf(p: ContrastPair): Spot =
    when (p) {
        ContrastPair.P1, ContrastPair.P2, ContrastPair.P3 -> Spot.LIST
        ContrastPair.P4, ContrastPair.P5, ContrastPair.P6 -> Spot.SEARCH
        ContrastPair.P7 -> Spot.PRESSED
        ContrastPair.P8 -> Spot.ACCENT
    }

/**
 * Live preview of the edited theme (THEME_SYSTEM §13): clock and date, favourites, a letter header, app rows (the
 * shared [AppRow]), a pressed row, an accent control, the search bar (the shared [SearchPillFrame]) and the index, over
 * a stand-in wallpaper drawn through the real backdrop ([strataBackdrop]). [problems] marks the spots of failing
 * pairs with a label and a black-and-white outline (never colour alone).
 */
@Composable
fun ThemePreview(
    theme: ResolvedTheme,
    apps: List<String>,
    wallpaperColours: List<Int>,
    wallpaperOn: Boolean,
    problems: Set<ContrastPair>,
    modifier: Modifier = Modifier,
) {
    val spots = problems.mapTo(HashSet(), ::spotOf)
    StrataTheme(theme) {
        val colors = Strata.colors
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(PREVIEW_HEIGHT)
                    .clip(RoundedCornerShape(12.dp))
                    .testTag(EditorTestTags.PREVIEW),
        ) {
            WallpaperStandIn(wallpaperOn, wallpaperColours)
            Box(modifier = Modifier.fillMaxSize().strataBackdrop(theme, colors))
            Row(modifier = Modifier.fillMaxSize()) {
                val indexShown = theme.alphabetIndex.visibility != IndexVisibility.NEVER
                if (indexShown && theme.alphabetIndex.side == IndexSide.START) PreviewIndex()
                Column(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                    if (theme.search.position == SearchPosition.TOP) PreviewSearch(Spot.SEARCH in spots)
                    PreviewHeader(theme)
                    if (theme.favorites.verticalPosition != VerticalPosition.BOTTOM) PreviewFavorites(theme, apps)
                    PreviewList(theme, apps, Spot.LIST in spots, Spot.PRESSED in spots, Spot.ACCENT in spots)
                    if (theme.favorites.verticalPosition == VerticalPosition.BOTTOM) PreviewFavorites(theme, apps)
                    if (theme.search.position == SearchPosition.BOTTOM) PreviewSearch(Spot.SEARCH in spots)
                }
                if (indexShown && theme.alphabetIndex.side == IndexSide.END) PreviewIndex()
            }
        }
    }
}

@Composable
private fun WallpaperStandIn(
    on: Boolean,
    colours: List<Int>,
) {
    val brush =
        if (!on) {
            Brush.verticalGradient(listOf(Color.Black, Color.Black))
        } else {
            val cs = colours.map { Color(it) }.ifEmpty { SAMPLE_WALLPAPER }
            Brush.linearGradient(if (cs.size == 1) listOf(cs[0], cs[0]) else cs)
        }
    Box(modifier = Modifier.fillMaxSize().background(brush))
}

private fun textAlign(a: ThemeAlignment): TextAlign =
    when (a) {
        ThemeAlignment.START -> TextAlign.Start
        ThemeAlignment.CENTER -> TextAlign.Center
        ThemeAlignment.END -> TextAlign.End
    }

@Composable
private fun PreviewHeader(theme: ResolvedTheme) {
    val colors = Strata.colors
    val c = theme.clock
    if (c.visible) {
        val size = (c.sizeSp * theme.typography.scale).sp
        val weight = FontWeight(theme.typography.clockWeight.coerceIn(100, 900))
        val h = if (c.showSeconds) "10:08:30" else "10:08"
        when (c.style) {
            ClockStyle.ANALOG_MINIMAL -> {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    AnalogClock(colors.textPrimary, Modifier.size((c.sizeSp * 1.2f).dp).align(alignmentOf(c.alignment)))
                }
            }

            ClockStyle.NUMERIC_STACKED -> {
                for (part in listOf("10", "08")) {
                    Text(
                        part,
                        color = colors.textPrimary,
                        fontSize = size,
                        lineHeight = size,
                        fontWeight = weight,
                        textAlign = textAlign(c.alignment),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }

            else -> {
                Text(
                    h,
                    color = colors.textPrimary,
                    fontSize = size,
                    fontWeight = weight,
                    textAlign = textAlign(c.alignment),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        }
    }
    if (c.dateVisible) {
        Text(
            "Monday 4 November",
            color = colors.textSecondary,
            fontSize = (c.dateSizeSp * theme.typography.scale).sp,
            textAlign = textAlign(c.alignment),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }
}

private fun alignmentOf(a: ThemeAlignment): Alignment =
    when (a) {
        ThemeAlignment.START -> Alignment.CenterStart
        ThemeAlignment.CENTER -> Alignment.Center
        ThemeAlignment.END -> Alignment.CenterEnd
    }

@Composable
private fun AnalogClock(
    colour: Color,
    modifier: Modifier,
) {
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(colour, radius = r, center = c, style = Stroke(2.dp.toPx()))
        drawLine(colour, c, Offset(c.x + r * 0.45f, c.y - r * 0.2f), 4.dp.toPx(), StrokeCap.Round)
        drawLine(colour, c, Offset(c.x, c.y - r * 0.8f), 2.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun PreviewFavorites(
    theme: ResolvedTheme,
    apps: List<String>,
) {
    val colors = Strata.colors
    val f = theme.favorites
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(f.spacingDp.dp),
    ) {
        for (label in apps.take(FAVORITES)) {
            Text(
                theme.typography.labelCase.apply(label, LocalConfiguration.current.locales[0]),
                color = colors.textPrimary,
                fontSize = (LABEL_SP * theme.typography.scale * f.textScale).sp,
                textAlign = textAlign(f.alignment),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PreviewList(
    theme: ResolvedTheme,
    apps: List<String>,
    listProblem: Boolean,
    pressedProblem: Boolean,
    accentProblem: Boolean,
) {
    val colors = Strata.colors
    Box(modifier = Modifier.fillMaxWidth().problemMark(listProblem)) {
        Column {
            if (theme.list.sectionHeaders) {
                Text(
                    apps.firstOrNull()?.take(1)?.uppercase() ?: "A",
                    style = Strata.typography.section,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = theme.list.horizontalPaddingDp.dp, vertical = 4.dp),
                )
            }
            for (label in apps.drop(FAVORITES).take(LIST_ROWS).ifEmpty { apps.take(LIST_ROWS) }) {
                AppRow(label = label, onClick = {})
                if (theme.list.showDividers) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                }
            }
        }
    }
    Box(modifier = Modifier.problemMark(pressedProblem)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(colors.highlight)
                    .padding(horizontal = theme.list.horizontalPaddingDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(apps.lastOrNull() ?: "Weather", style = Strata.typography.label, color = colors.textPrimary)
        }
    }
    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).problemMark(accentProblem)) {
        Box(
            modifier =
                Modifier
                    .background(colors.accent, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.editor_apply), color = colors.onAccent, style = Strata.typography.label)
        }
    }
}

@Composable
private fun PreviewSearch(problem: Boolean) {
    val colors = Strata.colors
    val theme = Strata.theme
    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).problemMark(problem)) {
        SearchPillFrame {
            if (theme.search.showHint) {
                Text(
                    stringResource(app.strata.launcher.core.ui.R.string.search_hint),
                    style = Strata.typography.label,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
            Box(modifier = Modifier.weight(1f))
            Box(modifier = Modifier.width(2.dp).height(20.dp).background(colors.accent))
        }
    }
}

@Composable
private fun PreviewIndex() {
    val colors = Strata.colors
    Column(
        modifier = Modifier.width(24.dp).padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for ((i, l) in "ABCDEFGH".withIndex()) {
            Text(
                l.toString(),
                style = Strata.typography.index,
                color = if (i == 1) colors.accent else colors.textSecondary,
            )
        }
    }
}

/** A failing spot: black-and-white outline plus a "Hard to read" label (text, not colour alone, §7.6). */
@Composable
private fun Modifier.problemMark(on: Boolean): Modifier {
    if (!on) return this
    val label = stringResource(R.string.editor_preview_problem_mark)
    return this
        .border(3.dp, Color.Black)
        .padding(1.dp)
        .border(1.dp, Color.White)
        .semantics { contentDescription = label }
        .testTag(EditorTestTags.PROBLEM_MARK)
}

private val PREVIEW_HEIGHT = 440.dp
private const val FAVORITES = 3
private const val LIST_ROWS = 3
private const val LABEL_SP = 16.0
private val SAMPLE_WALLPAPER = listOf(Color(0xFF3E6F8E), Color(0xFFC9A86A), Color(0xFF2B2D42))

/** The sample list used when the user's apps are not wanted (or not loaded yet). */
val SAMPLE_APPS = listOf("Calendar", "Camera", "Clock", "Contacts", "Files", "Maps", "Messages", "Music", "Weather")
