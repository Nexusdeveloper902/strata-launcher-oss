package app.strata.launcher.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.strata.launcher.core.model.BadgeStyle
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.SecondaryLine
import app.strata.launcher.core.theme.model.IconShape
import java.text.BreakIterator
import java.util.Locale

object AppRowTestTags {
    const val ICON = "app_row_icon"
    const val LABEL = "app_row_label"
    const val SECONDARY = "app_row_secondary"
    const val WORK_BADGE = "app_row_work_badge"
    const val BADGE_DOT = "app_row_badge_dot"
    const val BADGE_COUNT = "app_row_badge_count"
    const val TAG = "app_row_tag"
}

private val SELECTION_RING_WIDTH = 2.dp
private val SELECTION_RING_RADIUS = 8.dp

/**
 * One list row (FR-LIST-03 base row, VISUAL_SPEC §5–§7): monogram tile (system icons arrive in M3),
 * label, optional secondary line (FR-APPS-11), work badge and notification badge. The row is one
 * accessibility node; the badges add their own descriptions to it.
 */
@Composable
fun AppRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: SecondaryLine? = null,
    profileKind: ProfileKind = ProfileKind.MAIN,
    badge: Int = 0,
    badgeStyle: BadgeStyle = BadgeStyle.DOT,
    /** Short status tag after the label, e.g. "Hidden" in search results (FR-SEARCH-03). */
    tag: String? = null,
    /** Keyboard selection (search results, FR-SEARCH-04): draws the focus ring and sets `selected`. */
    selected: Boolean = false,
) {
    val theme = Strata.theme
    val colors = Strata.colors
    val type = Strata.typography
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val locale = LocalLocale.current.platformLocale
    val shownLabel = theme.typography.labelCase.apply(label, locale)
    val iconsVisible = theme.icons.visible
    val iconSize = theme.icons.sizeDp.dp
    val secondaryText = secondary?.let { secondaryText(it) }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = maxOf(theme.list.rowHeightDp, MIN_TOUCH_DP).dp)
                .background(if (pressed || focused) colors.highlight else colors.background.copy(alpha = 0f))
                .then(
                    if (selected) {
                        Modifier
                            .border(SELECTION_RING_WIDTH, colors.accent, RoundedCornerShape(SELECTION_RING_RADIUS))
                            .semantics { this.selected = true }
                    } else {
                        Modifier
                    },
                ).clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                .padding(horizontal = theme.list.horizontalPaddingDp.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (iconsVisible) {
            Box(modifier = Modifier.size(iconSize)) {
                MonogramTile(
                    label = label,
                    size = iconSize,
                    shape = iconShape(),
                    modifier = Modifier.testTag(AppRowTestTags.ICON),
                )
                if (profileKind == ProfileKind.WORK) {
                    WorkBadge(modifier = Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp))
                }
                if (badge > 0) {
                    NotificationBadge(
                        count = badge,
                        style = badgeStyle,
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp),
                    )
                }
            }
        }
        // Hidden labels stay the row's accessible name (THEME_SYSTEM §4.6).
        val nameModifier =
            if (theme.icons.labelsVisible) Modifier else Modifier.semantics { contentDescription = shownLabel }
        Column(modifier = Modifier.weight(1f).then(nameModifier)) {
            if (theme.icons.labelsVisible) {
                Text(
                    text = shownLabel,
                    style = type.label,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(AppRowTestTags.LABEL),
                )
            }
            if (tag != null) {
                Text(
                    text = tag,
                    style = type.caption,
                    color = colors.textSecondary,
                    maxLines = 1,
                    modifier = Modifier.testTag(AppRowTestTags.TAG),
                )
            }
            if (secondaryText != null) {
                Text(
                    text = secondaryText,
                    style = type.caption,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(AppRowTestTags.SECONDARY),
                )
            }
        }
        if (!iconsVisible) {
            if (profileKind == ProfileKind.WORK) WorkBadge()
            if (badge > 0) NotificationBadge(count = badge, style = badgeStyle)
        }
    }
}

@Composable
private fun iconShape(): Shape {
    val icons = Strata.theme.icons
    return when (icons.shape) {
        IconShape.SYSTEM, IconShape.CIRCLE -> {
            CircleShape
        }

        IconShape.SQUIRCLE, IconShape.ROUNDED_SQUARE -> {
            RoundedCornerShape(
                percent = icons.cornerRadiusPercent.coerceIn(0, 50),
            )
        }

        IconShape.SQUARE -> {
            RectangleShape
        }
    }
}

/** Profile name + detail, joined by a localised format (no concatenation in UI code). */
@Composable
fun secondaryText(line: SecondaryLine): String? {
    val profile = line.profile?.let { profileName(it) }
    val detail = line.detail
    return when {
        profile != null && detail != null -> stringResource(R.string.secondary_line_format, profile, detail)
        profile != null -> profile
        else -> detail
    }
}

@Composable
fun profileName(kind: ProfileKind): String =
    stringResource(
        when (kind) {
            ProfileKind.MAIN -> R.string.profile_main
            ProfileKind.WORK -> R.string.profile_work
            ProfileKind.PRIVATE -> R.string.profile_private
            ProfileKind.OTHER -> R.string.profile_other
        },
    )

/**
 * Generated placeholder icon (INTERACTION_SPEC §8): the label's first grapheme on `surface` in
 * `textSecondary`. Decorative; the row carries the label.
 */
@Composable
fun MonogramTile(
    label: String,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
) {
    val colors = Strata.colors
    val type = Strata.typography
    val glyph = remember(label) { firstGrapheme(label) }
    Box(
        // Decorative: the row already carries the label.
        modifier = modifier.size(size).background(colors.surface, shape).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = type.label.copy(fontSize = type.label.fontSize * MONOGRAM_SCALE, fontWeight = FontWeight.Medium),
            color = colors.textSecondary,
            maxLines = 1,
        )
    }
}

/** First user-perceived character, upper-cased where the script has case. */
fun firstGrapheme(label: String): String {
    val text = label.trim()
    if (text.isEmpty()) return ""
    val it = BreakIterator.getCharacterInstance()
    it.setText(text)
    val end = it.next().takeIf { e -> e != BreakIterator.DONE } ?: text.length
    return text.substring(0, end).uppercase(Locale.ROOT)
}

/** 16 dp work glyph (VISUAL_SPEC §7) drawn in code: a small briefcase on `surface`. */
@Composable
fun WorkBadge(modifier: Modifier = Modifier) {
    val colors = Strata.colors
    val description = stringResource(R.string.profile_work)
    Canvas(
        modifier =
            modifier
                .size(16.dp)
                .testTag(AppRowTestTags.WORK_BADGE)
                .semantics { contentDescription = description },
    ) {
        val w = size.width
        val h = size.height
        drawCircle(colors.surface)
        val bodyTop = h * 0.38f
        drawRoundRect(
            color = colors.textSecondary,
            topLeft = Offset(w * 0.2f, bodyTop),
            size = Size(w * 0.6f, h * 0.42f),
            cornerRadius = CornerRadius(w * 0.08f),
        )
        drawRoundRect(
            color = colors.textSecondary,
            topLeft = Offset(w * 0.38f, h * 0.24f),
            size = Size(w * 0.24f, h * 0.16f),
            cornerRadius = CornerRadius(w * 0.04f),
            style =
                androidx.compose.ui.graphics.drawscope
                    .Stroke(width = w * 0.07f),
        )
    }
}

/** Dot (8 dp) or count pill (min 16 dp, "99+" cap) in `accent` / `onAccent` (VISUAL_SPEC §7, FR-NOTIF-02). */
@Composable
fun NotificationBadge(
    count: Int,
    style: BadgeStyle,
    modifier: Modifier = Modifier,
) {
    val colors = Strata.colors
    val description = pluralStringResource(R.plurals.badge_description, count, count)
    when (style) {
        BadgeStyle.DOT -> {
            Box(
                modifier =
                    modifier
                        .size(8.dp)
                        .background(colors.accent, CircleShape)
                        .testTag(AppRowTestTags.BADGE_DOT)
                        .semantics { contentDescription = description },
            )
        }

        BadgeStyle.COUNT -> {
            val text =
                if (count > MAX_COUNT) {
                    stringResource(R.string.badge_count_overflow)
                } else {
                    stringResource(R.string.badge_count, count)
                }
            Box(
                modifier =
                    modifier
                        .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                        .background(colors.accent, RoundedCornerShape(percent = 50))
                        .padding(horizontal = 4.dp)
                        .testTag(AppRowTestTags.BADGE_COUNT)
                        .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    style = Strata.typography.caption.copy(fontWeight = FontWeight.Bold),
                    color = colors.onAccent,
                )
            }
        }
    }
}

private const val MIN_TOUCH_DP = 48
private const val MAX_COUNT = 99
private const val MONOGRAM_SCALE = 1.1f
