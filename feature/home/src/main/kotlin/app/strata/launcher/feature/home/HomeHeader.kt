package app.strata.launcher.feature.home

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
) {
    /** Hours, minutes and seconds joined with the locale separator. */
    val time: String get() = listOfNotNull(hours, minutes, seconds).joinToString(separator)
}

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
    ): ClockText {
        val pattern = pattern(use24h, showSeconds, locale)
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
        return ClockText(
            hours = field(tokens.getOrNull(hourIdx)) ?: "",
            minutes = field(tokens.getOrNull(minuteIdx)) ?: "",
            seconds = if (showSeconds) field(tokens.firstOrNull { it.field && it.text[0] == 's' }) else null,
            period = if (use24h) null else field(tokens.getOrNull(periodIdx)),
            separator = separator,
            periodFirst = !use24h && periodIdx in 0 until hourIdx,
            full = SimpleDateFormat(pattern, locale).apply { this.timeZone = timeZone }.format(date),
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
    val horizontal =
        when (clock.alignment) {
            ThemeAlignment.START -> Alignment.Start
            ThemeAlignment.CENTER -> Alignment.CenterHorizontally
            ThemeAlignment.END -> Alignment.End
        }
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
                remember(millis / DAY_BUCKET_MS, clock.dateSkeleton, locale) {
                    val pattern =
                        runCatching { DateFormat.getBestDateTimePattern(locale, clock.dateSkeleton) }
                            .getOrDefault(DEFAULT_DATE_PATTERN)
                    SimpleDateFormat(pattern, locale).format(Date(millis))
                }
            Text(
                text = date,
                style = Strata.typography.label.copy(fontSize = clock.dateSizeSp.sp, fontWeight = FontWeight.Normal),
                color = Strata.colors.textSecondary,
                textAlign = TextAlign.Start,
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
            Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
                val period: @Composable () -> Unit = {
                    if (text.period != null) {
                        Text(
                            text = text.period,
                            style = base.copy(fontSize = (size * PERIOD_SCALE).sp),
                            color = colors.textPrimary,
                            maxLines = 1,
                            modifier = Modifier.testTag(HomeTestTags.CLOCK_PERIOD),
                        )
                    }
                }
                if (text.periodFirst) period()
                Text(
                    text = text.time,
                    style = base,
                    color = colors.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.testTag(HomeTestTags.CLOCK_TIME),
                )
                if (!text.periodFirst) period()
            }
        }

        ClockStyle.NUMERIC_STACKED -> {
            val stacked = base.copy(lineHeight = (size * STACKED_LINE_HEIGHT).sp)
            Column(modifier = modifier) {
                Text(text = text.hours, style = stacked, color = colors.textPrimary, maxLines = 1)
                Text(text = text.minutes, style = stacked, color = colors.textSecondary, maxLines = 1)
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

private const val SECOND_MS = 1_000L
private const val DAY_BUCKET_MS = 60_000L
private const val LINE_HEIGHT = 1.15f
private const val STACKED_LINE_HEIGHT = 0.9f
private const val PERIOD_SCALE = 0.3f
private const val DEG_PER_HOUR = 30f
private const val DEG_PER_MINUTE = 6f
private const val DEFAULT_DATE_PATTERN = "EEEE d MMMM"
