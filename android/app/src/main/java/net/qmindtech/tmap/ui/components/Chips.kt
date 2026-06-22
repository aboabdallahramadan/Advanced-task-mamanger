package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapType

/**
 * General-purpose action/tag chip.
 *
 * @param label      Text shown inside the chip.
 * @param onClick    Invoked when the chip is tapped.
 * @param modifier   Optional outer modifier.
 * @param leadingEmoji Optional emoji prefix (e.g. capture-category icon).
 * @param selected   When true, fills with accent color; text uses [TmapColorScheme.onAccent].
 */
@Composable
fun Chip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingEmoji: String? = null,
    selected: Boolean = false,
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val type = LocalTmapType.current
    Row(
        modifier = modifier
            .background(
                color = if (selected) colors.accent else colors.surfaceInset,
                shape = RoundedCornerShape(shapes.pill),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!leadingEmoji.isNullOrBlank()) {
            Text(
                text = leadingEmoji,
                style = type.meta,
            )
        }
        Text(
            text = label,
            style = type.meta,
            color = if (selected) colors.onAccent else colors.textSecondary,
        )
    }
}

/**
 * Boolean filter chip with an amber accent border when selected.
 *
 * Selected state: [TmapColorScheme.surfaceRaised] fill + [TmapColorScheme.accent] border +
 * accent text.
 * Idle state: [TmapColorScheme.surfaceInset] fill + [TmapColorScheme.borderSubtle] border +
 * secondary text.
 */
@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val type = LocalTmapType.current
    Row(
        modifier = modifier
            .background(
                color = if (selected) colors.surfaceRaised else colors.surfaceInset,
                shape = RoundedCornerShape(shapes.pill),
            )
            .border(
                width = 1.dp,
                color = if (selected) colors.accent else colors.borderSubtle,
                shape = RoundedCornerShape(shapes.pill),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = type.meta,
            color = if (selected) colors.accent else colors.textSecondary,
        )
    }
}

/**
 * Pill segmented toggle — FIXED contract consumed by Browse (P2), Notes (P4), Today List/Timeline
 * toggle (P1/P7).
 *
 * Container: [TmapColorScheme.surfaceInset], fully pill-rounded.
 * Active segment: [TmapColorScheme.surfaceRaised] fill + [TmapColorScheme.accent] text.
 * Idle segment: transparent fill + [TmapColorScheme.textSecondary] text.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val type = LocalTmapType.current
    Row(
        modifier = Modifier
            .background(colors.surfaceInset, RoundedCornerShape(shapes.pill))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            Text(
                text = label,
                style = type.meta,
                color = if (isSelected) colors.accent else colors.textSecondary,
                modifier = Modifier
                    .background(
                        color = if (isSelected) colors.surfaceRaised else Color.Transparent,
                        shape = RoundedCornerShape(shapes.pill),
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}
