package net.qmindtech.tmap.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.qmindtech.tmap.ui.theme.LocalReduceMotion
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing

@Composable
fun SwipeableTaskCard(
    task: TaskUi,
    onToggleComplete: () -> Unit,
    onDefer: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val spacing = LocalTmapSpacing.current
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val thresholdPx = with(LocalDensity.current) { 96.dp.toPx() }

    Box(modifier = modifier.fillMaxWidth()) {
        // Reveal layer: green complete (start side) / red defer-delete (end side).
        val decision = resolveSwipe(offsetX.value, thresholdPx)
        Row(
            modifier = Modifier
                .matchParentSize()
                .background(
                    color = when {
                        offsetX.value > 0f -> colors.success
                        offsetX.value < 0f -> colors.danger
                        else -> colors.surface
                    },
                    shape = RoundedCornerShape(shapes.card),
                )
                .padding(horizontal = spacing.xxl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (offsetX.value > 0f) Arrangement.Start else Arrangement.End,
        ) {
            if (offsetX.value > 0f) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onAccent)
            } else if (offsetX.value < 0f) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = colors.onAccent)
            }
        }

        // Foreground card translates with the drag.
        TaskCard(
            task = task,
            onToggleComplete = onToggleComplete,
            onClick = onClick,
            modifier = Modifier
                .graphicsLayer { translationX = offsetX.value }
                .pointerInput(task.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                when (resolveSwipe(offsetX.value, thresholdPx).action) {
                                    SwipeAction.Complete -> { offsetX.snapTo(0f); onToggleComplete() }
                                    SwipeAction.DeferDelete -> { offsetX.snapTo(0f); onDefer() }
                                    SwipeAction.None -> if (reduceMotion) {
                                        offsetX.snapTo(0f)
                                    } else {
                                        offsetX.animateTo(0f, animationSpec = spring())
                                    }
                                }
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                        },
                    )
                },
        )
    }
}
