package net.qmindtech.tmap.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.components.TaskUi
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapType
import java.time.LocalTime

/** A task projected onto the timeline: its UI row data + resolved local start + duration (min). */
data class TimelineBlock(
    val ui: TaskUi,
    val start: LocalTime,
    val durationMin: Int,
)

// Left-gutter width; hour labels live in [0, GUTTER), blocks start at GUTTER.
private val GUTTER: Dp = 58.dp

// Blocks end 18dp from the end edge.
private val BLOCK_END_PAD: Dp = 18.dp

// Shift hour label upward so its baseline aligns with the guide-line.
private val LABEL_BASELINE_NUDGE: Dp = 7.dp

// Small top pad so the first label (09) isn't flush with the top of the scroll container.
private val RAIL_TOP_PAD: Dp = 6.dp

/**
 * The time-blocked Today view.
 *
 * Renders a scrollable vertical hour rail (09–22) with:
 * - Hour labels + faint vertical guide line in the start gutter.
 * - Task blocks absolutely positioned by [blockOffsetDp] / [blockHeightDp].
 * - Amber now-line at [nowLineOffsetDp].
 * - A dashed empty-slot hint below the now-line.
 * - Long-press drag of any block calls [dropOffsetToTime] and fires [onTimeblock].
 *
 * All offsets are in the local coordinate system of the outer Box rail.
 */
@Composable
fun TimelineContent(
    blocks: List<TimelineBlock>,
    now: LocalTime,
    onClick: (String) -> Unit,
    onTimeblock: (taskId: String, start: LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current
    val density = LocalDensity.current

    val totalRailHeight = TimelineDefaults.railHeight + RAIL_TOP_PAD

    Box(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(totalRailHeight),
        ) {

            // ── Vertical guide line ──────────────────────────────────────────
            Box(
                modifier = Modifier
                    .offset(x = GUTTER - 1.dp, y = 0.dp)
                    .width(1.dp)
                    .height(totalRailHeight)
                    .background(colors.borderSubtle),
            )

            // ── Hour labels ─────────────────────────────────────────────────
            for (hour in TimelineDefaults.RAIL_START_HOUR until TimelineDefaults.RAIL_END_HOUR) {
                val y = blockOffsetDp(LocalTime.of(hour, 0)) + RAIL_TOP_PAD - LABEL_BASELINE_NUDGE
                Text(
                    text = "%02d".format(hour),
                    color = colors.textTertiary,
                    style = type.label,
                    modifier = Modifier
                        .width(GUTTER - 10.dp)
                        .offset(x = 4.dp, y = y)
                        .semantics { contentDescription = "%02d:00".format(hour) },
                )
            }

            // ── Scheduled task blocks ────────────────────────────────────────
            blocks.forEach { block ->
                TimelineTaskBlock(
                    block = block,
                    topPad = RAIL_TOP_PAD,
                    endPad = BLOCK_END_PAD,
                    density = density,
                    onClick = { onClick(block.ui.id) },
                    onDragEnd = { dropY -> onTimeblock(block.ui.id, dropOffsetToTime(dropY)) },
                )
            }

            // ── Amber now-line ───────────────────────────────────────────────
            val nowY = nowLineOffsetDp(now) + RAIL_TOP_PAD
            // Guard: only render if within the visible rail window.
            if (nowY >= RAIL_TOP_PAD && nowY <= totalRailHeight) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(x = 0.dp, y = nowY)
                        .padding(end = BLOCK_END_PAD)
                        .semantics { contentDescription = "Current time indicator" },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // Hairline extending from gutter to end.
                    Box(
                        modifier = Modifier
                            .offset(x = GUTTER - 4.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.accent),
                    )
                    // Amber dot on the guide line.
                    Box(
                        modifier = Modifier
                            .offset(x = GUTTER - 8.dp)
                            .width(8.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(colors.accent),
                    )
                }
            }

            // ── Empty drop-slot hint (below the now-line) ────────────────────
            val hintY = nowLineOffsetDp(now) + RAIL_TOP_PAD + 12.dp
            val hintShape = RoundedCornerShape(12.dp)
            Box(
                modifier = Modifier
                    .offset(x = GUTTER, y = hintY)
                    .padding(end = BLOCK_END_PAD)
                    .fillMaxWidth()
                    .height(TimelineDefaults.minBlockHeight)
                    .clip(hintShape)
                    .border(
                        width = 1.dp,
                        color = colors.borderSubtle,
                        shape = hintShape,
                    )
                    .semantics { contentDescription = "Drag a task here to time-block" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Drag a task here to time-block",
                    color = colors.textTertiary,
                    style = type.meta,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Private block card
// ---------------------------------------------------------------------------

@Composable
private fun TimelineTaskBlock(
    block: TimelineBlock,
    topPad: Dp,
    endPad: Dp,
    density: Density,
    onClick: () -> Unit,
    onDragEnd: (Dp) -> Unit,
) {
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current
    val baseOffset: Dp = blockOffsetDp(block.start) + topPad
    val blockHeight: Dp = blockHeightDp(block.durationMin)
    val cardShape = RoundedCornerShape(12.dp)
    val barColor: Color = block.ui.projectColor?.let { Color(it) } ?: colors.accent

    // Drag delta accumulated during long-press drag (reset on release).
    var dragDy by remember(block.ui.id) { mutableStateOf(0.dp) }

    Box(
        modifier = Modifier
            .offset(x = GUTTER, y = baseOffset + dragDy)
            .padding(end = endPad)
            .fillMaxWidth()
            .height(blockHeight)
            .clip(cardShape)
            .background(colors.surface)
            .border(width = 1.dp, color = colors.borderSubtle, shape = cardShape)
            // Tap → open editor.
            .pointerInput(block.ui.id + "tap") {
                detectTapGestures(onTap = { onClick() })
            }
            // Long-press drag → re-time-block.
            .pointerInput(block.ui.id + "drag") {
                detectDragGesturesAfterLongPress(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragDy += with(density) { dragAmount.y.toDp() }
                    },
                    onDragEnd = {
                        // baseOffset already excludes topPad in the rail coordinate system.
                        val railOffset = baseOffset - topPad + dragDy
                        onDragEnd(railOffset)
                        dragDy = 0.dp
                    },
                    onDragCancel = { dragDy = 0.dp },
                )
            }
            .semantics {
                contentDescription = block.ui.title +
                    (block.ui.scheduledLabel?.let { ", scheduled $it" } ?: "")
            },
    ) {
        // Project-colored left accent bar.
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxSize()
                .background(barColor),
        )

        // Title + scheduled meta.
        Column(
            modifier = Modifier
                .padding(start = 11.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = block.ui.title,
                color = colors.textPrimary,
                style = type.body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (block.ui.scheduledLabel != null) {
                Text(
                    text = buildScheduledMeta(block),
                    color = colors.textSecondary,
                    style = type.meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Thin amber progress sliver at the bottom for partway-done blocks.
        if (block.ui.subtaskTotal > 0 && block.ui.subtaskDone in 1 until block.ui.subtaskTotal) {
            val frac = block.ui.subtaskDone.toFloat() / block.ui.subtaskTotal
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 11.dp, end = 8.dp, bottom = 6.dp)
                    .fillMaxWidth(frac)
                    .height(3.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(
                        Brush.horizontalGradient(listOf(colors.accent, colors.accentEnd)),
                    ),
            )
        }
    }
}

/** "HH:mm–HH:mm · Project" meta label for the block card. */
private fun buildScheduledMeta(block: TimelineBlock): String {
    val projectPart = block.ui.projectName?.let { " · $it" } ?: ""
    return "${block.ui.scheduledLabel}$projectPart"
}
