package net.qmindtech.tmap.ui.planning.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.ProjectDot
import net.qmindtech.tmap.ui.components.SectionLabel
import net.qmindtech.tmap.ui.planning.PlanItemUi
import net.qmindtech.tmap.ui.planning.PlanningUiState
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapType
import net.qmindtech.tmap.ui.theme.TmapTheme

/**
 * Timebox step — shows the picked tasks (those with [PlanItemUi.added] == true across
 * [PlanningUiState.carryOver] + [PlanningUiState.inboxPicks], falling back to resolving
 * [PlanningUiState.pickedIds] against the same pool) with their estimated duration label.
 *
 * NOTE: Full drag-to-time-block on a scrollable hour-rail timeline is deferred to P7.
 * This step closes the ritual with the commit dock (CapacityDock); a simple time-assign list
 * is shown here as specified in the brief.
 */
@Composable
fun TimeboxStep(state: PlanningUiState, modifier: Modifier = Modifier) {
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current

    // Resolve the picked items: prefer items marked added=true, fall back to pickedIds lookup.
    val pool = (state.carryOver + state.inboxPicks).associateBy { it.id }
    val picked = pool.values.filter { it.added }.ifEmpty {
        state.pickedIds.mapNotNull { pool[it] }
    }

    if (picked.isEmpty()) {
        EmptyState(
            icon = Icons.Default.DateRange,
            title = "No tasks picked",
            subtitle = "Go back to Pick Your Day and add some tasks.",
            modifier = modifier,
        )
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            SectionLabel(
                text = "Your day",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
            )
        }
        item {
            Text(
                text = "Drag-to-time-block lands in the Timeline (P7).",
                style = type.meta,
                color = colors.textTertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
        }
        items(picked, key = { it.id }) { item ->
            TimeboxRow(item = item)
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun TimeboxRow(item: PlanItemUi) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val type = LocalTmapType.current

    val durationLabel = when {
        item.durationMinutes != null && item.durationMinutes > 0 -> {
            val h = item.durationMinutes / 60
            val m = item.durationMinutes % 60
            when {
                h > 0 && m > 0 -> "${h}h ${m}m"
                h > 0 -> "${h}h"
                else -> "${m}m"
            }
        }
        else -> "Unscheduled"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .background(color = colors.surface, shape = RoundedCornerShape(shapes.card))
            .padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = item.title,
                style = type.body,
                color = colors.textPrimary,
            )
            if (!item.projectName.isNullOrBlank() || item.projectColor != null) {
                Row(
                    modifier = Modifier.padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (item.projectColor != null) {
                        ProjectDot(colorArgb = item.projectColor)
                    }
                    if (!item.projectName.isNullOrBlank()) {
                        Text(
                            text = item.projectName,
                            style = type.meta,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
        Text(
            text = durationLabel,
            style = type.meta,
            color = colors.textTertiary,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF191A20)
@Composable
private fun TimeboxStepPreview() {
    TmapTheme {
        TimeboxStep(
            state = PlanningUiState(
                loading = false,
                carryOver = listOf(
                    PlanItemUi(id = "1", title = "Review pull requests", projectName = "Work", projectColor = 0xFF6EA8FE, durationMinutes = 45, added = true),
                    PlanItemUi(id = "2", title = "Write spec doc", projectName = "Work", projectColor = 0xFF6EA8FE, durationMinutes = 90, added = true),
                ),
                inboxPicks = listOf(
                    PlanItemUi(id = "3", title = "Book dentist appointment", projectName = "Health", projectColor = 0xFFF0A868, durationMinutes = null, added = true),
                ),
                pickedIds = listOf("1", "2", "3"),
                plannedMinutes = 165,
                workdayMinutes = 360,
            ),
        )
    }
}
