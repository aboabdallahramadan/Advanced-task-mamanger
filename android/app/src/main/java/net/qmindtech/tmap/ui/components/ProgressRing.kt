package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.theme.LocalTmapColors

/** Clamped progress → degrees of sweep (0..360). */
fun sweepAngle(progress: Float): Float = progress.coerceIn(0f, 1f) * 360f

/**
 * Amber progress arc with a centered label (e.g. "3 of 8" or a percentage).
 *
 * The ring is drawn on a Canvas:
 * - Track: full 360° ring in [net.qmindtech.tmap.ui.theme.TmapColorScheme.borderSubtle].
 * - Sweep: amber [net.qmindtech.tmap.ui.theme.TmapColorScheme.accent]→[net.qmindtech.tmap.ui.theme.TmapColorScheme.accentEnd]
 *   gradient arc, starting at 12 o'clock (−90°), sweeping clockwise by `progress * 360°`.
 * - Stroke width: 8 dp (raw dp — no token exists for ring stroke width).
 *
 * Used by Focus mode (P6) and the Progress/Focus Glance widgets (P8).
 *
 * @param progress    Completion ratio in [0f, 1f]; clamped automatically.
 * @param modifier    Applied to the outer [Box] that hosts the Canvas and label.
 * @param centerLabel Composable rendered at the ring's center (e.g. a percentage Text).
 */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    centerLabel: @Composable () -> Unit,
) {
    val colors = LocalTmapColors.current
    val sweep = sweepAngle(progress)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val strokeWidth = 8.dp.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(inset, inset)
            // Track — full muted ring.
            drawArc(
                color = colors.borderSubtle,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth),
            )
            // Amber sweep — accent→accentEnd gradient arc.
            if (sweep > 0f) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(colors.accent, colors.accentEnd)),
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth),
                )
            }
        }
        centerLabel()
    }
}
