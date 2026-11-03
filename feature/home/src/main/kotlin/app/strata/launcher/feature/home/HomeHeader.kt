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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import app.strata.launcher.core.theme.model.Alignment as ThemeAlignment

/** Clock text for one instant (VISUAL_SPEC §9.4); pure so the 12/24 h rules are unit-tested. */
data class ClockText(val hours: String, val minutes: String, val seconds: String?, val period: String?)

object ClockFormat {
    fun format(
        millis: Long,
        use24h: Boolean,
        showSeconds: Boolean,
        locale: Locale,
    ): ClockText {
        val c = Calendar.getInstance(locale).apply { timeInMillis = millis }
        val h24 = c.get(Calendar.HOUR_OF_DAY)
        val hour = if (use24h) h24 else (h24 % 12).let { if (it == 0) 12 else it }
        val hours = if (use24h) "%02d".format(locale, hour) else "%d".format(locale, hour)
        val minutes = "%02d".format(locale, c.get(Calendar.MINUTE))
        val seconds = if (showSeconds) "%02d".format(locale, c.get(Calendar.SECOND)) else null
        val period =
            if (use24h) {
                null
            } else {
                java.text.DateFormatSymbols
                    .getInstance(locale)
                    .amPmStrings[c.get(Calendar.AM_PM)]
            }
        return ClockText(hours, minutes, seconds, period)
    }
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
            val spoken = remember(millis, use24h) { DateFormat.getTimeFormat(context).format(Date(millis)) }
            val text =
                remember(millis, use24h, clock.showSeconds, locale) {
                    ClockFormat.format(millis, use24h, clock.showSeconds, locale)
                }
            Clock(
                text = text,
                millis = millis,
                style = clock.style,
                modifier =
                    Modifier
                        .testTag(HomeTestTags.CLOCK)
                        .heightIn(min = 48.dp)
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
                Text(text = hm(text), style = base, color = colors.textPrimary, maxLines = 1)
                if (text.period != null) {
                    Text(
                        text = text.period,
                        style = base.copy(fontSize = (size * PERIOD_SCALE).sp),
                        color = colors.textPrimary,
                        maxLines = 1,
                    )
                }
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

@Composable
private fun hm(text: ClockText): String =
    if (text.seconds != null) {
        androidx.compose.ui.res
            .stringResource(R.string.clock_hms, text.hours, text.minutes, text.seconds)
    } else {
        androidx.compose.ui.res
            .stringResource(R.string.clock_hm, text.hours, text.minutes)
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
