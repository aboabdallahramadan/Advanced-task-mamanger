package net.qmindtech.tmap.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.components.SectionLabel
import net.qmindtech.tmap.ui.components.SwipeableTaskCard

// Maps each TodaySection to its display label (uppercase, per mockup).
private fun TodaySection.label(): String = when (this) {
    TodaySection.Morning -> "THIS MORNING"
    TodaySection.Afternoon -> "THIS AFTERNOON"
    TodaySection.Evening -> "THIS EVENING"
    TodaySection.Other -> "PLANNED"
}

/**
 * Lazy column of [TodayGroup]s separated by [SectionLabel] headers and rendered as
 * [SwipeableTaskCard]s.
 *
 * Long-press drag reorder: [onReorder] is plumbed per the FIXED contract so callers compile.
 * The gesture itself is deferred — [SwipeableTaskCard] from P0 exposes no drag handle slot,
 * so reorder will be wired in the dedicated P7 pass once the drag-handle API lands.
 */
@Composable
fun TodayListContent(
    groups: List<TodayGroup>,
    onToggleComplete: (String) -> Unit,
    onDefer: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClick: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        groups.forEach { group ->
            item(key = "section-${group.section.name}") {
                SectionLabel(
                    text = group.section.label(),
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                )
            }
            items(group.tasks, key = { it.id }) { task ->
                SwipeableTaskCard(
                    task = task,
                    onToggleComplete = { onToggleComplete(task.id) },
                    onDefer = { onDefer(task.id) },
                    onDelete = { onDelete(task.id) },
                    onClick = { onClick(task.id) },
                )
            }
        }
    }
}
