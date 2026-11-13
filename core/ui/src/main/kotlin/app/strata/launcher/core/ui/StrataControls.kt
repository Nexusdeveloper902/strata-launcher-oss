package app.strata.launcher.core.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
 */

private val PILL = RoundedCornerShape(50)
private val CHIP = RoundedCornerShape(8.dp)

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = M3TextButton(onClick = onClick, modifier = modifier.focusRing(PILL), enabled = enabled, content = content)

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = M3Button(onClick = onClick, modifier = modifier.focusRing(PILL), enabled = enabled, content = content)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = M3OutlinedButton(onClick = onClick, modifier = modifier.focusRing(PILL), enabled = enabled, content = content)

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
    modifier = modifier.focusRing(CHIP),
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
    modifier = modifier.focusRing(PILL),
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
    modifier = modifier.focusRing(CircleShape),
    enabled = enabled,
)

@Composable
fun RadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = M3RadioButton(selected = selected, onClick = onClick, modifier = modifier.focusRing(CircleShape), enabled = enabled)

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
    modifier = modifier.focusRing(CHIP),
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
) = M3DropdownMenuItem(text = text, onClick = onClick, modifier = modifier.focusRing(CHIP), enabled = enabled)
