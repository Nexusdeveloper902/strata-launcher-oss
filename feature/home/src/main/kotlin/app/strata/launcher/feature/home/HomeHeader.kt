package app.strata.launcher.feature.home

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.strata.launcher.core.theme.model.ClockStyle
import app.strata.launcher.core.theme.model.HourFormat
import app.strata.launcher.core.ui.SegmentedDigits
import app.strata.launcher.core.ui.SevenSegment
import app.strata.launcher.core.ui.Strata
import app.strata.launcher.core.ui.focusRing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.text.Bidi
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

/** The wall clock the header shows; the host provides the graph's (tests fix it). */
val LocalWallClock = staticCompositionLocalOf<() -> Long> { System::currentTimeMillis }

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
    now: () -> Long = LocalWallClock.current,
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
            val dateModifier =
                Modifier
                    .heightIn(min = 48.dp)
                    .focusRing()
                    .clickable(onClick = onDate)
                    .semantics { role = Role.Button }
                    .testTag(HomeTestTags.DATE)
            val dateStyle = Strata.typography.label.copy(fontSize = clock.dateSizeSp.sp, fontWeight = FontWeight.Normal)
            if (clock.visible && clock.style == ClockStyle.COUNTDOWN_BOARD) {
                StatusLine(text = date, style = dateStyle, locale = locale, modifier = dateModifier)
            } else {
                Text(
                    text = date,
                    style = dateStyle,
                    color = Strata.colors.textSecondary,
                    textAlign = textAlignOf(clock.alignment),
                    modifier = dateModifier,
                )
            }
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
            val periodStyle = base.copy(fontSize = (size * PERIOD_SCALE).sp)
            val centred = theme.clock.alignment == ThemeAlignment.CENTER
            TimeWithPeriod(
                centred = centred,
                periodFirst = text.periodFirst,
                ink =
                    if (centred) {
                        ClockInk(text.time, rememberBearings(base), text.period, rememberBearings(periodStyle))
                    } else {
                        null
                    },
                modifier = modifier,
                time = {
                    Text(
                        text = text.time,
                        style = base,
                        color = colors.textPrimary,
                        maxLines = 1,
                        modifier = Modifier.testTag(HomeTestTags.CLOCK_TIME),
                    )
                },
                period = {
                    if (text.period != null) {
                        Text(
                            text = text.period,
                            style = periodStyle,
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

        ClockStyle.SEGMENTED, ClockStyle.COUNTDOWN_BOARD -> {
            val cells = SevenSegment.cells(segmentText(text))
            if (cells == null) {
                // Digits the display cannot draw: the plain one-line time.
                Text(text = text.full, style = base, color = colors.textPrimary, maxLines = 1, modifier = modifier)
            } else {
                SegmentedClock(
                    cells = cells,
                    period = text.period,
                    periodFirst = text.periodFirst,
                    sizeSp = size,
                    board = style == ClockStyle.COUNTDOWN_BOARD,
                    modifier = modifier,
                )
            }
        }
    }
}

/** The display string of the segmented styles: the locale's hour, minute and second fields, colons between. */
internal fun segmentText(text: ClockText): String =
    listOfNotNull(text.hours, text.minutes, text.seconds).joinToString(":")

/**
 * `segmented` / `countdownBoard` (VISUAL_SPEC §9.4): seven-segment digits 0.72 × `clock.sizeSp` tall in `textPrimary`,
 * unlit bars in `textPrimary` at 10 %; the 12 h day period beside them at 30 % size. The board adds a "T−" label in
 * `accent` before the digits. Laid out left to right in every direction (digits are never mirrored); the parent still
 * places the whole block per `clock.alignment`.
 */
@Composable
private fun SegmentedClock(
    cells: List<Int>,
    period: String?,
    periodFirst: Boolean,
    sizeSp: Int,
    board: Boolean,
    modifier: Modifier,
) {
    val colors = Strata.colors
    val density = LocalDensity.current
    val digitHeight = with(density) { (sizeSp * SEGMENT_HEIGHT).sp.toDp() }
    val small = Strata.typography.label.copy(fontSize = (sizeSp * PERIOD_SCALE).sp, fontWeight = FontWeight.Medium)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
            if (board) {
                Text(
                    text = BOARD_PREFIX,
                    style = small.copy(letterSpacing = PREFIX_TRACKING.em),
                    color = colors.accent,
                    maxLines = 1,
                    modifier =
                        Modifier
                            .align(Alignment.Top)
                            .padding(end = digitHeight * PREFIX_GAP),
                )
            }
            val periodText: @Composable (Boolean) -> Unit = { before ->
                if (period != null) {
                    Text(
                        text = period,
                        style = small,
                        color = colors.textPrimary,
                        maxLines = 1,
                        modifier = if (before) Modifier.padding(end = 4.dp) else Modifier.padding(start = 4.dp),
                    )
                }
            }
            if (periodFirst) periodText(true)
            SegmentedDigits(
                cells = cells,
                height = digitHeight,
                lit = colors.textPrimary,
                ghost = colors.textPrimary.copy(alpha = GHOST_ALPHA),
                modifier = Modifier.weight(1f, fill = false),
            )
            if (!periodFirst) periodText(false)
        }
    }
}

/**
 * The `countdownBoard` status line: a small `accent` lamp, then the date in upper case (the locale's case mapping)
 * with wide tracking, in `textSecondary`.
 */
@Composable
private fun StatusLine(
    text: String,
    style: TextStyle,
    locale: Locale,
    modifier: Modifier,
) {
    val colors = Strata.colors
    val lamp = with(LocalDensity.current) { (style.fontSize * LAMP_SCALE).toDp() }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(lamp)) { drawCircle(colors.accent) }
        Text(
            text = text.uppercase(locale),
            style = style.copy(letterSpacing = STATUS_TRACKING.em, fontWeight = FontWeight.Medium),
            color = colors.textSecondary,
            maxLines = 1,
            modifier = Modifier.padding(start = lamp),
        )
    }
}

/**
 * The time and the day period side by side, bottoms aligned (period after the time, or before it when the locale puts
 * it first; mirrored in RTL). When [centred], the box reserves the period's width on both sides, so a parent that
 * centres the box centres the time itself (FB-007). Both always fit the given width: the time is measured with the
 * period's width (or both reserves) deducted, and when the time does not fit beside two reserves, the box falls back to
 * time + period, which the parent still centres as a whole.
 */
@Composable
private fun TimeWithPeriod(
    centred: Boolean,
    periodFirst: Boolean,
    ink: ClockInk?,
    modifier: Modifier,
    time: @Composable () -> Unit,
    period: @Composable () -> Unit,
) {
    Layout(contents = listOf(time, period), modifier = modifier) { (timeMeasurables, periodMeasurables), constraints ->
        val maxWidth = constraints.maxWidth
        val p =
            periodMeasurables
                .firstOrNull()
                ?.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = maxWidth / 2))
        val pw = p?.width ?: 0
        val timeMeasurable = timeMeasurables.firstOrNull()
        val timeWanted = timeMeasurable?.maxIntrinsicWidth(Constraints.Infinity) ?: 0
        val reserveBoth = centred && p != null && (!constraints.hasBoundedWidth || timeWanted <= maxWidth - 2 * pw)
        val reserve = if (reserveBoth) 2 * pw else pw
        val timeMax = if (constraints.hasBoundedWidth) (maxWidth - reserve).coerceAtLeast(0) else Constraints.Infinity
        val t = timeMeasurable?.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = timeMax))
        val tw = t?.width ?: 0
        // A time too wide even for the room left (an extreme size on a narrow screen) is clipped: its glyph bearings say
        // nothing about what is drawn, so it is not ink-shifted.
        val clipped = constraints.hasBoundedWidth && timeWanted > timeMax
        val height = maxOf(t?.height ?: 0, p?.height ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        val width = (tw + reserve).coerceIn(constraints.minWidth, maxWidth)
        layout(width, height) {
            val timeX =
                when {
                    reserveBoth -> (width - tw) / 2
                    periodFirst -> pw
                    else -> 0
                }
            val periodX = if (periodFirst) timeX - pw else timeX + tw
            // Absolute positions (placeRelative mirrors them in RTL), for the ink, which is measured left to right.
            val rtl = layoutDirection == LayoutDirection.Rtl
            val timeAbs = if (rtl) width - timeX - tw else timeX
            val periodAbs = if (rtl) width - periodX - pw else periodX
            // Reserved: the time's ink on the box centre. Fallback: the ink of time and period together.
            val periodInk = if (reserveBoth || p == null) null else periodAbs
            val shift = if (clipped || ink == null) 0 else ink.shift(width, rtl, timeAbs, tw, periodInk, pw)
            t?.place(timeAbs - shift, height - t.height)
            p?.place(periodAbs - shift, height - p.height)
        }
    }
}

/**
 * One line of clock text. With [centreInk], the drawn glyphs (not the advance box) are centred: digits have unequal
 * side bearings ("1" has wide ones), which put a centred box's ink up to a few dp off-centre (FB-007). Only the visually
 * first and last glyph decide where the ink starts and ends, so their side bearings are measured once per character and
 * style ([GlyphBearings]). The shift is computed at placement from this text and the measured width, so it is never a
 * frame behind a tick, and it recomposes nothing.
 */
@Composable
private fun InkText(
    text: String,
    style: TextStyle,
    color: Color,
    centreInk: Boolean,
    modifier: Modifier = Modifier,
) {
    val bearings = rememberBearings(style)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        modifier =
            modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    val shift = if (centreInk) bearings.inkShift(text, placeable.width, rtl) else 0
                    // Absolute: the bearings are measured left to right whatever the layout direction.
                    placeable.place(-shift, 0)
                }
            },
    )
}

@Composable
private fun rememberBearings(style: TextStyle): GlyphBearings {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    return remember(style, density) { GlyphBearings(measurer, style) }
}

/** The time and day-period texts of a numeric clock and their bearings, for centring their ink (FB-007). */
internal class ClockInk(
    private val time: String,
    private val timeBearings: GlyphBearings,
    private val period: String?,
    private val periodBearings: GlyphBearings,
) {
    /**
     * Pixels by which the ink lies right of the centre of a box [width] wide: the time's ink (box at [timeX], [timeW]
     * wide), together with the period's when [periodX] is given. All positions are absolute.
     */
    fun shift(
        width: Int,
        rtl: Boolean,
        timeX: Int,
        timeW: Int,
        periodX: Int?,
        periodW: Int,
    ): Int {
        val t = timeBearings.insets(time, rtl) ?: return 0
        var left = timeX + t.first
        var right = timeX + timeW - t.second
        if (periodX != null && period != null) {
            periodBearings.insets(period, rtl)?.let { p ->
                left = minOf(left, periodX + p.first)
                right = maxOf(right, periodX + periodW - p.second)
            }
        }
        val centre = if (periodX == null) timeX + timeW / 2f else width / 2f
        return ((left + right) / 2f - centre).roundToInt()
    }
}

/** Left and right side bearings (px, from the advance box to the ink) of single characters in one style. */
internal class GlyphBearings(
    private val measurer: TextMeasurer,
    private val style: TextStyle,
) {
    private val cache = HashMap<Char, IntArray>()

    /**
     * Pixels by which the ink's centre lies right of the box's centre for one line of [text] drawn [width] px wide in a
     * paragraph that is right to left when [rtl] (0 without ink).
     */
    fun inkShift(
        text: String,
        width: Int,
        rtl: Boolean,
    ): Int {
        if (width <= 0) return 0
        val (inkLeft, rightInset) = insets(text, rtl) ?: return 0
        return ((inkLeft + width - rightInset) / 2f - width / 2f).roundToInt()
    }

    /** Ink insets (left, right) of one line of [text]: the bearings of its visually first and last glyph. */
    fun insets(
        text: String,
        rtl: Boolean,
    ): Pair<Int, Int>? {
        if (text.isEmpty()) return null
        val (first, last) = visualEnds(text, rtl)
        val left = of(first) ?: return null
        val right = of(last) ?: return null
        return left[0] to right[1]
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

/**
 * The visually leftmost and rightmost characters of one line of [text]. Digits run left to right even in a right-to-left
 * paragraph; letters of a right-to-left script do not.
 */
internal fun visualEnds(
    text: String,
    rtl: Boolean,
): Pair<Char, Char> {
    val bidi = Bidi(text, if (rtl) Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT else Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
    if (bidi.isLeftToRight) return text.first() to text.last()
    val count = bidi.runCount
    val levels = ByteArray(count) { bidi.getRunLevel(it).toByte() }
    val order = Array<Any>(count) { it }
    Bidi.reorderVisually(levels, 0, order, 0, count)
    val leftRun = order.first() as Int
    val rightRun = order.last() as Int
    // An even level runs left to right: its first character is on its left.
    val leftLtr = bidi.getRunLevel(leftRun) % 2 == 0
    val rightLtr = bidi.getRunLevel(rightRun) % 2 == 0
    val left = if (leftLtr) text[bidi.getRunStart(leftRun)] else text[bidi.getRunLimit(leftRun) - 1]
    val right = if (rightLtr) text[bidi.getRunLimit(rightRun) - 1] else text[bidi.getRunStart(rightRun)]
    return left to right
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
private const val SEGMENT_HEIGHT = 0.72f
private const val GHOST_ALPHA = 0.1f
private const val BOARD_PREFIX = "T\u2212"
private const val PREFIX_TRACKING = 0.06f
private const val PREFIX_GAP = 0.16f
private const val STATUS_TRACKING = 0.12f
private const val LAMP_SCALE = 0.5f
