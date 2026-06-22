package net.qmindtech.tmap.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapMotion
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing
import net.qmindtech.tmap.ui.theme.LocalTmapType

@Composable
fun TaskCard(
    task: TaskUi,
    onToggleComplete: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val spacing = LocalTmapSpacing.current
    val type = LocalTmapType.current
    val motion = LocalTmapMotion.current

    val checkScale by animateFloatAsState(
        targetValue = if (task.isDone) 1f else 0.9f,
        animationSpec = tween(motion.checkOffMillis),
        label = "checkScale",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = if (task.isDone) colors.surfaceInset else colors.surface,
                shape = RoundedCornerShape(shapes.card),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.lg, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        // Circular checkbox: outlined when open, amber-filled with check when done.
        Box(
            modifier = Modifier
                .size(22.dp)
                .scale(checkScale)
                .background(
                    color = if (task.isDone) colors.accent else Color.Transparent,
                    shape = CircleShape,
                )
                .border(
                    width = 2.dp,
                    color = if (task.isDone) colors.accent else colors.borderStrong,
                    shape = CircleShape,
                )
                .clickable(onClick = onToggleComplete)
                .semantics { },
            contentAlignment = Alignment.Center,
        ) {
            if (task.isDone) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Mark not done",
                    tint = colors.onAccent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = type.body,
                color = if (task.isDone) colors.textTertiary else colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
            )
            val meta = buildMeta(task)
            if (meta != null) {
                Row(
                    modifier = Modifier.padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (task.projectColor != null) ProjectDot(colorArgb = task.projectColor)
                    Text(meta, style = type.meta, color = colors.textSecondary)
                }
            }
        }

        if (task.hasReminder) {
            Icon(
                Icons.Outlined.Notifications,
                contentDescription = "Has reminder",
                tint = colors.textTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** "Work · 9:30–10:15", "Work · 2 subtasks", "9:30", or null. */
private fun buildMeta(task: TaskUi): String? {
    val parts = mutableListOf<String>()
    task.projectName?.let { parts.add(it) }
    val tail = task.scheduledLabel
        ?: if (task.subtaskTotal > 0) "${task.subtaskTotal} subtasks" else null
    tail?.let { parts.add(it) }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}
