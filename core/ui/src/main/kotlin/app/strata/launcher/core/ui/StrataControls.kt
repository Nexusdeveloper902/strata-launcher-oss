package app.strata.launcher.core.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.Checkbox as M3Checkbox
import androidx.compose.material3.DropdownMenuItem as M3DropdownMenuItem
import androidx.compose.material3.FilterChip as M3FilterChip
import androidx.compose.material3.OutlinedButton as M3OutlinedButton
import androidx.compose.material3.RadioButton as M3RadioButton
import androidx.compose.material3.Slider as M3Slider
import androidx.compose.material3.Switch as M3Switch
import androidx.compose.material3.TextButton as M3TextButton

/*
 * Material controls with the FR-A11Y-04 focus ring. Material draws only a faint state layer on keyboard focus, so
 * screens import these instead of the `androidx.compose.material3` ones (same names, same parameters as used here).
 * Each also reserves the 48 dp touch target (NFR-A11Y-03); chips do not do that by themselves.
 */

private val PILL = RoundedCornerShape(50)
private val CHIP = RoundedCornerShape(8.dp)

/** NFR-A11Y-03: buttons and chips are laid out at least this tall (Material's own 40/32 dp rely on touch padding). */
private val MIN_TARGET = 48.dp

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = M3TextButton(
    onClick = onClick,
    modifier = modifier.heightIn(min = MIN_TARGET).focusRing(PILL),
    enabled = enabled,
    content = content,
)

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = M3Button(
    onClick = onClick,
    modifier = modifier.heightIn(min = MIN_TARGET).focusRing(PILL),
    enabled = enabled,
    content = content,
)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = M3OutlinedButton(
    onClick = onClick,
    modifier = modifier.heightIn(min = MIN_TARGET).focusRing(PILL),
    enabled = enabled,
    content = content,
)

@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = M3FilterChip(
    selected = selected,
    onClick = onClick,
    label = label,
    modifier = modifier.heightIn(min = MIN_TARGET).focusRing(CHIP),
    enabled = enabled,
)

@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = M3Switch(
    checked = checked,
    onCheckedChange = onCheckedChange,
    modifier = modifier.heightIn(min = MIN_TARGET).focusRing(PILL),
    enabled = enabled,
)

@Composable
fun Checkbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = M3Checkbox(
    checked = checked,
    onCheckedChange = onCheckedChange,
    modifier = modifier.minimumInteractiveComponentSize().heightIn(min = MIN_TARGET).focusRing(CircleShape),
    enabled = enabled,
)

@Composable
fun RadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = M3RadioButton(
    selected = selected,
    onClick = onClick,
    modifier = modifier.minimumInteractiveComponentSize().heightIn(min = MIN_TARGET).focusRing(CircleShape),
    enabled = enabled,
)

@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) = M3Slider(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier.minimumInteractiveComponentSize().focusRing(CHIP),
    enabled = enabled,
    valueRange = valueRange,
    steps = steps,
    onValueChangeFinished = onValueChangeFinished,
)

@Composable
fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = M3DropdownMenuItem(
    text = text,
    onClick = onClick,
    modifier = modifier.heightIn(min = MIN_TARGET).focusRing(CHIP),
    enabled = enabled,
)
