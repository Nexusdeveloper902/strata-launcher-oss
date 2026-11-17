package app.strata.launcher.feature.home

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.strata.launcher.core.theme.model.ClockStyle
import app.strata.launcher.core.theme.model.HourFormat
import app.strata.launcher.core.ui.Strata
import app.strata.launcher.core.ui.focusRing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import app.strata.launcher.core.theme.model.Alignment as ThemeAlignment

/**
 * Clock text for one instant (VISUAL_SPEC §9.4, NFR-I18N-04): the pieces of the locale's best time pattern for the
 * `Hm`/`hm` skeleton (plus `s`), so separator, digits and the position of the day period follow the locale; only the
 * 12/24 h choice comes from the caller (system setting or theme override).
 */
data class ClockText(
    val hours: String,
    val minutes: String,
    val seconds: String?,
    val period: String?,
    val separator: String = ":",
    /** The day period comes before the time (e.g. ja `午後9:05`). */
    val periodFirst: Boolean = false,
    /** The whole time as the locale writes it; the spoken text. */
    val full: String = "",
    /**
     * The time without the day period, with the locale's own literals between and around the fields (fr-CA
     * `21 h 05 min 07 s`, bg `21:05:07 ч.`).
     */
    val time: String = listOfNotNull(hours, minutes, seconds).joinToString(separator),
)

object ClockFormat {
    fun pattern(
        use24h: Boolean,
        showSeconds: Boolean,
        locale: Locale,
    ): String {
        val skeleton = (if (use24h) "Hm" else "hm") + if (showSeconds) "s" else ""
        val best = runCatching { DateFormat.getBestDateTimePattern(locale, skeleton) }.getOrNull()
        return best?.takeIf { p -> p.any { it in HOUR_LETTERS } && p.contains('m') }
            ?: ((if (use24h) "HH:mm" else "h:mm") + (if (showSeconds) ":ss" else "") + if (use24h) "" else " a")
    }

    fun format(
        millis: Long,
        use24h: Boolean,
        showSeconds: Boolean,
        locale: Locale,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): ClockText = format(millis, use24h, showSeconds, locale, timeZone, pattern(use24h, showSeconds, locale))

    /**
     * [pattern] as the clock shows it. A pattern the platform's formatter rejects falls back to `HH:mm` / `h:mm a`
     * (plus seconds), so a bad locale pattern can never throw on every tick.
     */
    internal fun format(
        millis: Long,
        use24h: Boolean,
        showSeconds: Boolean,
        locale: Locale,
        timeZone: TimeZone,
        pattern: String,
    ): ClockText =
        try {
            render(millis, use24h, showSeconds, locale, timeZone, pattern)
        } catch (e: RuntimeException) {
            render(millis, use24h, showSeconds, locale, timeZone, fallback(use24h, showSeconds))
        }

    private fun fallback(
        use24h: Boolean,
        showSeconds: Boolean,
    ) = (if (use24h) "HH:mm" else "h:mm") + (if (showSeconds) ":ss" else "") + if (use24h) "" else " a"

    private fun render(
        millis: Long,
        use24h: Boolean,
        showSeconds: Boolean,
        locale: Locale,
        timeZone: TimeZone,
        pattern: String,
    ): ClockText {
        val tokens = tokens(pattern)
        val date = Date(millis)

        fun field(t: Token?) =
            t?.let { SimpleDateFormat(it.text, locale).apply { this.timeZone = timeZone }.format(date) }
        val hourIdx = tokens.indexOfFirst { it.field && it.text[0] in HOUR_LETTERS }
        val minuteIdx = tokens.indexOfFirst { it.field && it.text[0] == 'm' }
        val periodIdx = tokens.indexOfFirst { it.field && it.text[0] in PERIOD_LETTERS }
        val separator =
            if (hourIdx >= 0 && minuteIdx > hourIdx) {
                tokens
                    .subList(hourIdx + 1, minuteIdx)
                    .filter { !it.field }
                    .joinToString("") { it.text }
                    .ifEmpty { ":" }
            } else {
                ":"
            }
        // The time is every token but the day period and the whitespace that joins it to the time.
        val timeTokens =
            tokens.filterIndexed { i, t ->
                when {
                    i == periodIdx -> false
                    !t.field && t.text.isBlank() && (i == periodIdx - 1 || i == periodIdx + 1) -> false
                    else -> true
                }
            }
        val time = timeTokens.joinToString("") { t -> if (t.field) field(t).orEmpty() else t.text }.trim()
        return ClockText(
            hours = field(tokens.getOrNull(hourIdx)) ?: "",
            minutes = field(tokens.getOrNull(minuteIdx)) ?: "",
            seconds = if (showSeconds) field(tokens.firstOrNull { it.field && it.text[0] == 's' }) else null,
            period = if (use24h) null else field(tokens.getOrNull(periodIdx)),
            separator = separator,
            periodFirst = !use24h && periodIdx in 0 until hourIdx,
            full = SimpleDateFormat(pattern, locale).apply { this.timeZone = timeZone }.format(date),
            time = time,
        )
    }

    private class Token(
        val text: String,
        val field: Boolean,
    )

    /** Splits a date pattern into letter runs (fields) and literals (quotes removed, `''` = `'`). */
    private fun tokens(pattern: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        val literal = StringBuilder()

        fun flush() {
            if (literal.isNotEmpty()) out += Token(literal.toString(), field = false)
            literal.clear()
        }
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\'' -> {
                    val close = pattern.indexOf('\'', i + 1)
                    if (close == i + 1) {
                        literal.append('\'')
                        i += 2
                    } else {
                        val end = if (close < 0) pattern.length else close
                        literal.append(pattern, i + 1, end)
                        i = end + 1
                    }
                }

                c in 'a'..'z' || c in 'A'..'Z' -> {
                    flush()
                    var j = i
                    while (j < pattern.length && pattern[j] == c) j++
                    out += Token(pattern.substring(i, j), field = true)
                    i = j
                }

                else -> {
                    literal.append(c)
                    i++
                }
            }
        }
        flush()
        return out
    }

    private const val HOUR_LETTERS = "hHkK"
    private const val PERIOD_LETTERS = "abB"
}

/**
 * Header (FR-HOME-02/03, VISUAL_SPEC §9.4): clock (tap → clock app) and date (tap → calendar), per the theme's
 * `clock` group. Updates on minute ticks; with `showSeconds`, once a second while resumed.
 */
@Composable
fun HomeHeader(
    ticks: Flow<Unit>,
    onClock: () -> Unit,
    onDate: () -> Unit,
    modifier: Modifier = Modifier,
    now: () -> Long = System::currentTimeMillis,
) {
    val clock = Strata.theme.clock
    if (!clock.visible && !clock.dateVisible) return
    var millis by remember { mutableLongStateOf(now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(ticks, clock.showSeconds) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (clock.showSeconds) {
                while (true) {
                    millis = now()
                    delay(SECOND_MS - millis % SECOND_MS)
                }
            } else {
                ticks.collect { millis = now() }
            }
        }
    }
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val use24h =
        when (clock.hourFormat) {
            HourFormat.SYSTEM -> DateFormat.is24HourFormat(context)
            HourFormat.H24 -> true
            HourFormat.H12 -> false
        }
    val horizontal = horizontalOf(clock.alignment)
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = horizontal,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (clock.visible) {
            val text =
                remember(millis, use24h, clock.showSeconds, locale) {
                    ClockFormat.format(millis, use24h, clock.showSeconds, locale)
                }
            // The spoken time follows the same 12/24 h choice as the digits (theme override included).
            val spoken = text.full
            Clock(
                text = text,
                millis = millis,
                style = clock.style,
                modifier =
                    Modifier
                        .testTag(HomeTestTags.CLOCK)
                        .heightIn(min = 48.dp)
                        .focusRing()
                        .clickable(onClick = onClock)
                        .clearAndSetSemantics {
                            contentDescription = spoken
                            role = Role.Button
                        },
            )
        }
        if (clock.dateVisible) {
            val date =
                // Keyed on the local day: the minute ticks leave the date (and the ICU pattern lookup) alone.
                remember(localDay(millis), clock.dateSkeleton, locale) {
                    val pattern =
                        runCatching { DateFormat.getBestDateTimePattern(locale, clock.dateSkeleton) }
                            .getOrDefault(DEFAULT_DATE_PATTERN)
                    SimpleDateFormat(pattern, locale).format(Date(millis))
                }
            Text(
                text = date,
                style = Strata.typography.label.copy(fontSize = clock.dateSizeSp.sp, fontWeight = FontWeight.Normal),
                color = Strata.colors.textSecondary,
                textAlign = textAlignOf(clock.alignment),
                modifier =
                    Modifier
                        .heightIn(min = 48.dp)
                        .focusRing()
                        .clickable(onClick = onDate)
                        .semantics { role = Role.Button }
                        .testTag(HomeTestTags.DATE),
            )
        }
    }
}

@Composable
private fun Clock(
    text: ClockText,
    millis: Long,
    style: ClockStyle,
    modifier: Modifier,
) {
    val theme = Strata.theme
    val colors = Strata.colors
    val size = theme.clock.sizeSp
    val base =
        Strata.typography.label.copy(
            fontSize = size.sp,
            lineHeight = (size * LINE_HEIGHT).sp,
            fontWeight = FontWeight(theme.typography.clockWeight.coerceIn(1, 1000)),
            fontFeatureSettings = "tnum",
        )
    when (style) {
        // `words` falls back to `numeric`: there is no words formatter (VISUAL_SPEC §9.4).
        ClockStyle.NUMERIC, ClockStyle.WORDS -> {
            // FB-007: centred, the time itself is centred and the day period hangs beside it.
            TimeWithPeriod(
                centred = theme.clock.alignment == ThemeAlignment.CENTER,
                periodFirst = text.periodFirst,
                modifier = modifier,
                time = {
                    InkText(
                        text = text.time,
                        style = base,
                        color = colors.textPrimary,
                        centreInk = theme.clock.alignment == ThemeAlignment.CENTER,
                        modifier = Modifier.testTag(HomeTestTags.CLOCK_TIME),
                    )
                },
                period = {
                    if (text.period != null) {
                        Text(
                            text = text.period,
                            style = base.copy(fontSize = (size * PERIOD_SCALE).sp),
                            color = colors.textPrimary,
                            maxLines = 1,
                            modifier = Modifier.testTag(HomeTestTags.CLOCK_PERIOD),
                        )
                    }
                },
            )
        }

        ClockStyle.NUMERIC_STACKED -> {
            val stacked = base.copy(lineHeight = (size * STACKED_LINE_HEIGHT).sp)
            Column(modifier = modifier, horizontalAlignment = horizontalOf(theme.clock.alignment)) {
                val centreInk = theme.clock.alignment == ThemeAlignment.CENTER
                InkText(text = text.hours, style = stacked, color = colors.textPrimary, centreInk = centreInk)
                InkText(text = text.minutes, style = stacked, color = colors.textSecondary, centreInk = centreInk)
            }
        }

        ClockStyle.ANALOG_MINIMAL -> {
            AnalogClock(
                millis = millis,
                diameterDp = size * 2,
                showSeconds = theme.clock.showSeconds,
                modifier = modifier,
            )
        }
    }
}

/**
 * The time and the day period side by side, bottoms aligned (period after the time, or before it when the locale puts
 * it first; mirrored in RTL). When [centred], the box reserves the period's width on both sides, so a parent that
 * centres the box centres the time itself (FB-007).
 */
@Composable
private fun TimeWithPeriod(
    centred: Boolean,
    periodFirst: Boolean,
    modifier: Modifier,
    time: @Composable () -> Unit,
    period: @Composable () -> Unit,
) {
    Layout(contents = listOf(time, period), modifier = modifier) { (timeMeasurables, periodMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val t = timeMeasurables.firstOrNull()?.measure(loose)
        val p = periodMeasurables.firstOrNull()?.measure(loose)
        val tw = t?.width ?: 0
        val pw = p?.width ?: 0
        val height = maxOf(t?.height ?: 0, p?.height ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        val width = (if (centred) tw + 2 * pw else tw + pw).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, height) {
            val timeX =
                when {
                    centred -> (width - tw) / 2
                    periodFirst -> pw
                    else -> 0
                }
            t?.placeRelative(timeX, height - t.height)
            p?.placeRelative(if (periodFirst) timeX - pw else timeX + tw, height - p.height)
        }
    }
}

/**
 * One line of clock text. With [centreInk], the drawn glyphs (not the advance box) are centred: digits have unequal
 * side bearings ("1" has wide ones), which put a centred box's ink up to a few dp off-centre (FB-007). Only the first
 * and the last glyph decide where the ink starts and ends, so their side bearings are measured once per character and
 * style ([GlyphBearings]); a tick costs two map reads. The shift is applied at placement, so it recomposes nothing.
 */
@Composable
private fun InkText(
    text: String,
    style: TextStyle,
    color: Color,
    centreInk: Boolean,
    modifier: Modifier = Modifier,
) {
    var shift by remember { mutableIntStateOf(0) }
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val bearings = remember(style, density) { GlyphBearings(measurer, style) }
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        onTextLayout = { shift = if (centreInk) bearings.inkShift(it) else 0 },
        modifier = modifier.absoluteOffset { IntOffset(-shift, 0) },
    )
}

/** Left and right side bearings (px, from the advance box to the ink) of single characters in one style. */
internal class GlyphBearings(
    private val measurer: TextMeasurer,
    private val style: TextStyle,
) {
    private val cache = HashMap<Char, IntArray>()

    /** Pixels by which the ink's centre lies right of the box's centre for [layout] (one line, 0 without ink). */
    fun inkShift(layout: TextLayoutResult): Int {
        val text = layout.layoutInput.text.text
        if (text.isEmpty()) return 0
        // The visually leftmost glyph: digits run left to right even in a right-to-left paragraph, so ask the layout.
        val rtl = layout.getBoundingBox(0).left > layout.getBoundingBox(text.length - 1).left
        val left = of(if (rtl) text.last() else text.first()) ?: return 0
        val right = of(if (rtl) text.first() else text.last()) ?: return 0
        val inkLeft = left[0]
        val inkRight = layout.size.width - right[1]
        return ((inkLeft + inkRight) / 2f - layout.size.width / 2f).roundToInt()
    }

    private fun of(c: Char): IntArray? = cache.getOrPut(c) { measure(c) }.takeIf { it[0] >= 0 }

    private fun measure(c: Char): IntArray {
        val glyph = measurer.measure(c.toString(), style.copy(color = Color.Black), maxLines = 1)
        val w = glyph.size.width
        val h = glyph.size.height
        if (w <= 0 || h <= 0) return intArrayOf(-1, -1)
        val image = ImageBitmap(w, h)
        TextPainter.paint(Canvas(image), glyph)
        val pixels = image.toPixelMap()
        var first = -1
        var last = -1
        for (x in 0 until w) {
            if ((0 until h).any { y -> pixels[x, y].alpha > INK_ALPHA }) {
                if (first < 0) first = x
                last = x
            }
        }
        return if (first < 0) intArrayOf(-1, -1) else intArrayOf(first, w - 1 - last)
    }

    private companion object {
        const val INK_ALPHA = 0.25f
    }
}

private fun horizontalOf(alignment: ThemeAlignment): Alignment.Horizontal =
    when (alignment) {
        ThemeAlignment.START -> Alignment.Start
        ThemeAlignment.CENTER -> Alignment.CenterHorizontally
        ThemeAlignment.END -> Alignment.End
    }

private fun textAlignOf(alignment: ThemeAlignment): TextAlign =
    when (alignment) {
        ThemeAlignment.START -> TextAlign.Start
        ThemeAlignment.CENTER -> TextAlign.Center
        ThemeAlignment.END -> TextAlign.End
    }

/** `analogMinimal` (VISUAL_SPEC §9.4): circle, one accent dot at 12, hour hand 4 dp, minute hand 2 dp, round caps. */
@Composable
private fun AnalogClock(
    millis: Long,
    diameterDp: Int,
    showSeconds: Boolean,
    modifier: Modifier,
) {
    val colors = Strata.colors
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    val minutes = c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60f
    val hours = (c.get(Calendar.HOUR) + minutes / 60f)
    val seconds = c.get(Calendar.SECOND)
    Canvas(modifier = modifier.size(diameterDp.dp)) {
        val r = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            colors.textSecondary,
            radius = r,
            center = center,
            style =
                androidx.compose.ui.graphics.drawscope
                    .Stroke(1.dp.toPx()),
        )
        drawCircle(colors.accent, radius = 3.dp.toPx(), center = Offset(center.x, center.y - r + 8.dp.toPx()))
        rotate(hours * DEG_PER_HOUR, center) {
            drawLine(colors.textPrimary, center, Offset(center.x, center.y - r * 0.5f), 4.dp.toPx(), StrokeCap.Round)
        }
        rotate(minutes * DEG_PER_MINUTE, center) {
            drawLine(colors.textPrimary, center, Offset(center.x, center.y - r * 0.8f), 2.dp.toPx(), StrokeCap.Round)
        }
        if (showSeconds) {
            rotate(seconds * DEG_PER_MINUTE, center) {
                drawLine(colors.accent, center, Offset(center.x, center.y - r * 0.85f), 1.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

/**
 * The day number of [millis] in [zone] (the default zone: a time-zone change is a tick, so the next read sees it). The
 * header's date is re-derived when this changes, i.e. at local midnight, never on the other minute ticks.
 */
internal fun localDay(
    millis: Long,
    zone: TimeZone = TimeZone.getDefault(),
): Long = Math.floorDiv(millis + zone.getOffset(millis), DAY_MS)

private const val SECOND_MS = 1_000L
private const val DAY_MS = 86_400_000L
private const val LINE_HEIGHT = 1.15f
private const val STACKED_LINE_HEIGHT = 0.9f
private const val PERIOD_SCALE = 0.3f
private const val DEG_PER_HOUR = 30f
private const val DEG_PER_MINUTE = 6f
private const val DEFAULT_DATE_PATTERN = "EEEE d MMMM"
