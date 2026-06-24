package net.qmindtech.tmap.ui.planning.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.SectionLabel
import net.qmindtech.tmap.ui.planning.PlanItemUi
import net.qmindtech.tmap.ui.planning.PlanningUiState
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapType
import net.qmindtech.tmap.ui.theme.TmapTheme

@Composable
fun ReflectStep(state: PlanningUiState, modifier: Modifier = Modifier) {
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current
    val done = state.yesterdayDone
    val undone = state.yesterdayUndone

    if (done.isEmpty() && undone.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Star,
            title = "Fresh start",
            subtitle = "Nothing carried over — you're all clear.",
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
    ) {
        if (done.isNotEmpty()) {
            item {
                SectionLabel(
                    text = "Completed yesterday",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                )
            }
            items(done, key = { it.id }) { item ->
                ReflectRow(item = item, showCheckmark = true)
            }
        }

        if (undone.isNotEmpty()) {
            item {
                SectionLabel(
                    text = "Still open",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                )
            }
            items(undone, key = { it.id }) { item ->
                ReflectRow(item = item, showCheckmark = false)
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun ReflectRow(item: PlanItemUi, showCheckmark: Boolean) {
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCheckmark) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = colors.success,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = type.body,
                color = if (showCheckmark) colors.textSecondary else colors.textPrimary,
            )
            if (!item.projectName.isNullOrBlank()) {
                Text(
                    text = item.projectName,
                    style = type.meta,
                    color = colors.textTertiary,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF191A20)
@Composable
private fun ReflectStepPreview() {
    TmapTheme {
        ReflectStep(
            state = PlanningUiState(
                loading = false,
                yesterdayDone = listOf(
                    PlanItemUi(id = "1", title = "Write weekly report", projectName = "Work", projectColor = null, durationMinutes = 30, done = true),
                ),
                yesterdayUndone = listOf(
                    PlanItemUi(id = "2", title = "Review pull requests", projectName = "Work", projectColor = null, durationMinutes = 45, done = false),
                    PlanItemUi(id = "3", title = "Go for a run", projectName = "Health", projectColor = null, durationMinutes = 60, done = false),
                ),
            ),
        )
    }
}
