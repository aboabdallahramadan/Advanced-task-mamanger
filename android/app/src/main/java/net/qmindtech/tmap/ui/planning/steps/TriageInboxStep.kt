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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.components.Chip
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.ProjectDot
import net.qmindtech.tmap.ui.components.SectionLabel
import net.qmindtech.tmap.ui.planning.PlanItemUi
import net.qmindtech.tmap.ui.planning.PlanningUiState
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapType
import net.qmindtech.tmap.ui.theme.TmapTheme

@Composable
fun TriageInboxStep(
    state: PlanningUiState,
    onSchedule: (String) -> Unit,
    onBacklog: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val inbox = state.inbox

    if (inbox.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Email,
            title = "Inbox zero",
            subtitle = "Nicely done — nothing to triage.",
            modifier = modifier,
        )
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            SectionLabel(
                text = "Your inbox",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
            )
        }
        items(inbox, key = { it.id }) { item ->
            InboxCard(
                item = item,
                onSchedule = { onSchedule(item.id) },
                onBacklog = { onBacklog(item.id) },
                onDelete = { onDelete(item.id) },
            )
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun InboxCard(
    item: PlanItemUi,
    onSchedule: () -> Unit,
    onBacklog: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val type = LocalTmapType.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .background(color = colors.surface, shape = RoundedCornerShape(shapes.card))
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Title + project meta
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
            // Delete icon button (icon-only → contentDescription required, a11y §9)
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete task",
                    tint = colors.danger,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        // One-tap action chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip(label = "Today", onClick = onSchedule)
            Chip(label = "Backlog", onClick = onBacklog)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF191A20)
@Composable
private fun TriageInboxStepPreview() {
    TmapTheme {
        TriageInboxStep(
            state = PlanningUiState(
                loading = false,
                inbox = listOf(
                    PlanItemUi(id = "1", title = "Reply to client email", projectName = "Work", projectColor = 0xFF6EA8FE, durationMinutes = 15),
                    PlanItemUi(id = "2", title = "Book dentist appointment", projectName = "Health", projectColor = 0xFFF0A868, durationMinutes = null),
                ),
            ),
            onSchedule = {},
            onBacklog = {},
            onDelete = {},
        )
    }
}
