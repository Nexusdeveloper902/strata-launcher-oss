package app.strata.launcher.core.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

/**
 * Modal bottom sheet of VISUAL_SPEC §2 ("Sheets"): scrim, `surface`, max width 640 dp, top corners 28 dp, 24 dp
 * padding, 32 × 4 dp handle in `divider`. Drawn in the home window (not a dialog), so it survives configuration
 * changes with the activity (FR-LIFE-02). It rises with `motion.medium` (instant with reduced motion); predictive
 * back drags it down and a cancelled gesture restores it (INTERACTION_SPEC §6).
 */
@Composable
fun StrataSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Strata.colors
    // §4.11: base × theme scale (Compose adds the system animator scale); 0 = no animation.
    val enterMs = Strata.theme.animation.durationMs(MEDIUM_MS)
    val animate = enterMs > 0
    val enter = remember { Animatable(if (animate) 0f else 1f) }
    var back by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        if (animate) enter.animateTo(1f, tween(durationMillis = enterMs, easing = EMPHASIZED_DECELERATE))
    }
    PredictiveBackHandler { progress ->
        try {
            progress.collect { back = it.progress }
            onDismiss()
        } catch (e: CancellationException) {
            back = 0f
            throw e
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = enter.value * (1f - back) }
                    .background(colors.scrim.copy(alpha = SCRIM_ALPHA))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ).semantics { hideFromAccessibility() },
        )
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = MAX_WIDTH)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val travel = size.height
                        translationY = travel * (1f - enter.value) + travel * BACK_TRAVEL * back
                    }.background(colors.surface, RoundedCornerShape(topStart = CORNER, topEnd = CORNER))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .heightIn(max = MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PADDING, vertical = 12.dp)
                    .semantics { isTraversalGroup = true },
        ) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 12.dp)
                        .size(width = 32.dp, height = 4.dp)
                        .background(colors.divider, RoundedCornerShape(2.dp)),
            )
            content()
        }
    }
}

/** One sheet / menu item: 48 dp minimum height, system font (VISUAL_SPEC §3), one button node. */
@Composable
fun SheetItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    /** A disabled item is read as disabled and drawn in `textSecondary` (never by colour alone: it has no action). */
    enabled: Boolean = true,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .padding(vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) Strata.colors.textPrimary else Strata.colors.textSecondary,
        )
    }
}

private val MAX_WIDTH = 640.dp
private val MAX_HEIGHT = 640.dp
private val CORNER = 28.dp
private val PADDING = 24.dp
private const val SCRIM_ALPHA = 0.4f
private const val BACK_TRAVEL = 0.3f

/** `motion.medium` (VISUAL_SPEC §10): the sheet's enter duration before the animation scales. */
internal const val MEDIUM_MS = 250

/** The emphasized decelerate curve of `motion.medium` (VISUAL_SPEC §10). */
private val EMPHASIZED_DECELERATE = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
