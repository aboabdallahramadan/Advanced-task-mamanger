package net.qmindtech.tmap.ui.planning.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.components.PrimaryButton
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapType
import net.qmindtech.tmap.ui.theme.TmapTheme

/**
 * Bottom dock shown at every planning step.
 *
 * Layout (top → bottom):
 *  1. A vertical scrim from transparent → [TmapColorScheme.bgBottom] to fade the list content.
 *  2. A solid [TmapColorScheme.bgBottom] panel containing:
 *     - A [Row] with "≈ Xh Ym planned of your Zh" ([textSecondary]) and "N%" ([textTertiary]).
 *     - A 5dp pill capacity bar ([surfaceInset] track, amber-gradient fill at [fraction] width).
 *     - A full-width amber [PrimaryButton] labelled [continueLabel].
 */
@Composable
fun CapacityDock(
    plannedMinutes: Int,
    workdayMinutes: Int,
    fraction: Float,
    continueLabel: String,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val type = LocalTmapType.current
    val accentBrush = Brush.linearGradient(listOf(colors.accent, colors.accentEnd))

    val plannedH = plannedMinutes / 60
    val plannedM = plannedMinutes % 60
    val workdayH = workdayMinutes / 60
    val plannedLabel = when {
        plannedH > 0 && plannedM > 0 -> "≈ ${plannedH}h ${plannedM}m planned of your ${workdayH}h"
        plannedH > 0 -> "≈ ${plannedH}h planned of your ${workdayH}h"
        else -> "≈ ${plannedM}m planned of your ${workdayH}h"
    }
    val percentLabel = "${(fraction * 100).toInt()}%"

    Column(modifier = modifier.fillMaxWidth()) {
        // Scrim: transparent → bgBottom (fades list content below the dock)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            colors.bgBottom.copy(alpha = 0f),
                            colors.bgBottom,
                        ),
                    ),
                ),
        )

        // Dock panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bgBottom)
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Capacity label row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = plannedLabel,
                    style = type.meta,
                    color = colors.textSecondary,
                )
                Text(
                    text = percentLabel,
                    style = type.meta,
                    color = colors.textTertiary,
                )
            }

            // Capacity bar: pill track with amber-gradient fill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(shapes.pill))
                    .background(colors.surfaceInset),
            ) {
                val fillFraction = fraction.coerceIn(0f, 1f)
                if (fillFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fillFraction)
                            .height(5.dp)
                            .clip(RoundedCornerShape(shapes.pill))
                            .background(brush = accentBrush),
                    )
                }
            }

            // Full-width amber primary button
            PrimaryButton(
                text = continueLabel,
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF191A20)
@Composable
private fun CapacityDockPreview() {
    TmapTheme {
        CapacityDock(
            plannedMinutes = 195,
            workdayMinutes = 360,
            fraction = 0.54f,
            continueLabel = "Continue →",
            onContinue = {},
        )
    }
}
