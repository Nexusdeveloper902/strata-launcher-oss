package app.strata.launcher.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.strata.launcher.core.model.IndexLetter
import kotlin.math.floor
import kotlin.math.roundToInt

object AlphabetIndexTestTags {
    const val INDEX = "alphabet_index"
    const val BUBBLE = "alphabet_index_bubble"

    fun letter(label: String) = "alphabet_index_letter_$label"
}

/**
 * System gesture exclusion of the index (INTERACTION_SPEC §4.2, FR-GEST-05). [NONE] while home is not the resumed
 * activity; [FULL] = the whole index rect; [AROUND_TOUCH] = "Keep back gesture on index side" (settings field 12).
 */
enum class IndexExclusion { NONE, FULL, AROUND_TOUCH }

/** Geometry and letter resolution of the index, separated from Compose for unit tests. */
object IndexMath {
    /** INTERACTION_SPEC §4.2 rule 3. */
    const val AROUND_TOUCH_DP = 200f
    const val INWARD_EXTENSION_DP = 16f

    /**
     * Excluded vertical span in the index's own coordinates (0 = its top, which already sits below the status-bar
     * inset and ends 24 dp above the bottom mandatory inset), or null for none. [around] and the result are in px.
     */
    fun exclusionSpan(
        mode: IndexExclusion,
        height: Float,
        lastTouchY: Float?,
        around: Float,
    ): ClosedFloatingPointRange<Float>? =
        when (mode) {
            IndexExclusion.NONE -> {
                null
            }

            IndexExclusion.FULL -> {
                0f..height
            }

            IndexExclusion.AROUND_TOUCH -> {
                val center = (lastTouchY ?: (height / 2f)).coerceIn(0f, height)
                val half = around / 2f
                val top = (center - half).coerceAtLeast(0f)
                val bottom = (center + half).coerceAtMost(height)
                top..bottom
            }
        }

    const val MIN_PITCH_DP = 14f
    const val MAX_PITCH_DP = 28f

    /** Letter under [y] for a track of [count] letters starting at [top] with height [height]. */
    fun letterAt(
        y: Float,
        top: Float,
        height: Float,
        count: Int,
    ): Int {
        if (count <= 0 || height <= 0f) return -1
        val f = ((y - top) / height).coerceIn(0f, 0.99999f)
        return floor(f * count).toInt()
    }

    /** FR-LIST-02: an empty letter snaps to the next section, or the previous one at the end. */
    fun resolve(
        letters: List<IndexLetter>,
        index: Int,
    ): Int {
        if (index !in letters.indices) return -1
        if (!letters[index].isEmpty) return index
        for (j in index + 1 until letters.size) if (!letters[j].isEmpty) return j
        for (j in index - 1 downTo 0) if (!letters[j].isEmpty) return j
        return -1
    }

    /**
     * Visible slots (VISUAL_SPEC §9.3): one per letter when they fit at [MIN_PITCH_DP]; otherwise every
     * other slot folds a run of letters into "•". Each slot is the letter range it stands for.
     */
    fun slots(
        count: Int,
        maxSlots: Int,
    ): List<IntRange> {
        if (count <= 0) return emptyList()
        if (count <= maxSlots || maxSlots < 3) return (0 until count).map { it..it }
        val n = if (maxSlots % 2 == 0) maxSlots - 1 else maxSlots
        return (0 until n).map { s ->
            val from = s * count / n
            val to = ((s + 1) * count / n - 1).coerceAtLeast(from)
            from..to
        }
    }
}

/**
 * The alphabet index (FR-LIST-02, VISUAL_SPEC §9.3): touch or drag jumps to the section under the
 * finger and shows a bubble. [onSelect] gets the resolved non-empty letter index; `first` is true for
 * the touch-down and false for each later letter change (the caller ticks haptics on changes).
 * Empty letters are dimmed (35 % α), not focusable, and snap to the next section.
 * [atEnd]: the index sits on the end edge (touch area extends toward the start) or on the start edge.
 */
@Composable
fun AlphabetIndex(
    letters: List<IndexLetter>,
    onSelect: (letterIndex: Int, first: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    atEnd: Boolean = true,
    dots: Boolean = false,
    showBubble: Boolean = true,
    exclusion: IndexExclusion = IndexExclusion.NONE,
) {
    val colors = Strata.colors
    val type = Strata.typography
    val density = LocalDensity.current
    val select by rememberUpdatedState(onSelect)
    var active by remember { mutableStateOf(false) }
    var current by remember { mutableIntStateOf(-1) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var lastTouchY by remember { mutableStateOf<Float?>(null) }
    val indexDescription = stringResource(R.string.index_description)

    BoxWithConstraints(
        modifier =
            modifier
                .width(TOUCH_WIDTH)
                .fillMaxHeight()
                .testTag(AlphabetIndexTestTags.INDEX)
                .indexExclusion(
                    exclusion,
                    // Read only where it matters: a read here recomposes the whole index on every pointer move of a
                    // scrub, and FULL / NONE ignore it (docs/perf/optimization-pass-1.md C-4).
                    if (exclusion == IndexExclusion.AROUND_TOUCH) lastTouchY else null,
                    atEnd,
                    around = with(density) { IndexMath.AROUND_TOUCH_DP.dp.toPx() },
                    inward = with(density) { IndexMath.INWARD_EXTENSION_DP.dp.toPx() },
                ).semantics {
                    contentDescription = indexDescription
                    isTraversalGroup = true
                },
    ) {
        val heightPx = constraints.maxHeight.toFloat()
        val minPitchPx = with(density) { IndexMath.MIN_PITCH_DP.dp.toPx() }
        val maxPitchPx = with(density) { IndexMath.MAX_PITCH_DP.dp.toPx() }
        val maxSlots = (heightPx / minPitchPx).toInt().coerceAtLeast(1)
        val slots = remember(letters.size, maxSlots) { IndexMath.slots(letters.size, maxSlots) }
        val pitchPx = if (slots.isEmpty()) 0f else (heightPx / slots.size).coerceIn(minPitchPx, maxPitchPx)
        val trackHeightPx = pitchPx * slots.size
        val trackTopPx = ((heightPx - trackHeightPx) / 2f).coerceAtLeast(0f)
        val pitch: Dp = with(density) { pitchPx.toDp() }
        val trackHeight: Dp = with(density) { trackHeightPx.toDp() }
        val trackTop: Dp = with(density) { trackTopPx.toDp() }

        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .pointerInput(letters, trackTopPx, trackHeightPx) {
                        fun handle(
                            y: Float,
                            first: Boolean,
                        ) {
                            fingerY = y
                            lastTouchY = y
                            val target =
                                IndexMath.resolve(
                                    letters,
                                    IndexMath.letterAt(y, trackTopPx, trackHeightPx, letters.size),
                                )
                            if (target < 0) return
                            if (first || target != current) {
                                current = target
                                select(target, first)
                            }
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            active = true
                            down.consume()
                            handle(down.position.y, first = true)
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                handle(change.position.y, first = false)
                            }
                            active = false
                        }
                    },
        )

        val trackWidth = if (active) ACTIVE_WIDTH else VISUAL_WIDTH
        Column(
            modifier =
                Modifier
                    .align(if (atEnd) Alignment.TopEnd else Alignment.TopStart)
                    .offset(y = trackTop)
                    .width(trackWidth)
                    .height(trackHeight)
                    .background(
                        if (active) {
                            colors.surface.copy(
                                alpha = ACTIVE_TRACK_ALPHA,
                            )
                        } else {
                            colors.surface.copy(alpha = 0f)
                        },
                        RoundedCornerShape(percent = 50),
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            slots.forEachIndexed { s, range ->
                val folded = range.first != range.last
                val i = range.first
                val letter = letters[i]
                val isCurrent = active && current in range
                val color =
                    when {
                        isCurrent -> colors.accent
                        letter.isEmpty && !folded -> colors.textSecondary.copy(alpha = EMPTY_ALPHA)
                        else -> colors.textSecondary.copy(alpha = IDLE_ALPHA)
                    }
                val glyph = if (dots || (folded && s % 2 == 1)) stringResource(R.string.index_fold) else letter.label
                val letterDescription =
                    pluralStringResource(
                        R.plurals.index_letter_description,
                        letter.appCount,
                        letter.label,
                        letter.appCount,
                    )
                val semanticsModifier =
                    if (letter.isEmpty || (folded && s % 2 == 1)) {
                        Modifier.clearAndSetSemantics {}
                    } else {
                        Modifier.semantics {
                            role = Role.Button
                            contentDescription = letterDescription
                            onClick {
                                current = i
                                select(i, true)
                                true
                            }
                        }
                    }
                Box(
                    modifier =
                        Modifier
                            .height(
                                pitch,
                            ).testTag(AlphabetIndexTestTags.letter(letter.label))
                            .then(semanticsModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = glyph,
                        style = type.index,
                        color = color,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.scale(if (isCurrent) ACTIVE_SCALE else 1f),
                    )
                }
            }
        }

        if (active && showBubble && current in letters.indices) {
            val bubbleX =
                if (atEnd) {
                    TOUCH_WIDTH - ACTIVE_WIDTH - BUBBLE_GAP - BUBBLE_SIZE
                } else {
                    ACTIVE_WIDTH +
                        BUBBLE_GAP
                }
            Box(
                modifier =
                    Modifier
                        // The finger position is read in the layout phase only, so a drag within one letter moves
                        // the bubble without recomposing the index (C-4).
                        .offset {
                            IntOffset(bubbleX.roundToPx(), (fingerY - (BUBBLE_SIZE / 2).toPx()).roundToInt())
                        }.size(BUBBLE_SIZE)
                        .background(colors.surface, RoundedCornerShape(12.dp))
                        .testTag(AlphabetIndexTestTags.BUBBLE)
                        .semantics { hideFromAccessibility() },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = letters[current].label, style = type.bubble, color = colors.textPrimary, maxLines = 1)
            }
        }
    }
}

/** Requests the system gesture exclusion of [mode] (API 29+; a no-op below). Sizes in px. */
private fun Modifier.indexExclusion(
    mode: IndexExclusion,
    lastTouchY: Float?,
    atEnd: Boolean,
    around: Float,
    inward: Float,
): Modifier {
    if (mode == IndexExclusion.NONE) return this
    val extension = if (mode == IndexExclusion.AROUND_TOUCH) inward else 0f
    return systemGestureExclusion { coordinates ->
        val w = coordinates.size.width.toFloat()
        val span =
            IndexMath.exclusionSpan(mode, coordinates.size.height.toFloat(), lastTouchY, around)
                ?: return@systemGestureExclusion Rect.Zero
        val left = if (atEnd) -extension else 0f
        val right = if (atEnd) w else w + extension
        Rect(left, span.start, right, span.endInclusive)
    }
}

private val TOUCH_WIDTH = 48.dp
private val VISUAL_WIDTH = 24.dp
private val ACTIVE_WIDTH = 32.dp
private val BUBBLE_SIZE = 64.dp
private val BUBBLE_GAP = 24.dp
private const val ACTIVE_TRACK_ALPHA = 0.9f
private const val IDLE_ALPHA = 0.7f
private const val EMPTY_ALPHA = 0.35f
private const val ACTIVE_SCALE = 1.3f
