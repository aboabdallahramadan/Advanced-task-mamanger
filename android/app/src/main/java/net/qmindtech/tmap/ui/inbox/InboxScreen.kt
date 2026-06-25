package net.qmindtech.tmap.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.ui.components.Chip
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.ProjectDot
import net.qmindtech.tmap.ui.components.TaskUi
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing
import net.qmindtech.tmap.ui.theme.LocalTmapType

@Composable
fun InboxScreen(
    onOpenTask: (taskId: String) -> Unit,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current
    val spacing = LocalTmapSpacing.current

    Column {
        // Header
        Text(
            text = "Inbox · ${uiState.count}",
            style = type.heading,
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
        )

        if (uiState.tasks.isEmpty() && !uiState.loading) {
            EmptyState(
                icon = Icons.Filled.Inbox,
                title = "Inbox Zero",
                subtitle = "All clear — nothing waiting for a decision.",
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                modifier = Modifier.padding(horizontal = spacing.lg),
            ) {
                items(uiState.tasks, key = { it.id }) { task ->
                    InboxItemCard(
                        task = task,
                        projects = uiState.projects,
                        onOpenTask = { onOpenTask(task.id) },
                        onSchedule = { viewModel.schedule(task.id) },
                        onBacklog = { viewModel.backlog(task.id) },
                        onAssignProject = { projectId -> viewModel.assignProject(task.id, projectId) },
                        onDelete = { viewModel.delete(task.id) },
                    )
                }
                item { Spacer(Modifier.height(spacing.lg)) }
            }
        }
    }
}

@Composable
private fun InboxItemCard(
    task: TaskUi,
    projects: List<ProjectEntity>,
    onOpenTask: () -> Unit,
    onSchedule: () -> Unit,
    onBacklog: () -> Unit,
    onAssignProject: (projectId: String) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val spacing = LocalTmapSpacing.current
    val type = LocalTmapType.current

    var showPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(shapes.card))
            .clickable(onClick = onOpenTask)
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        // Title row with optional project dot
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            if (task.projectColor != null) {
                ProjectDot(colorArgb = task.projectColor)
            }
            Text(
                text = task.title,
                style = type.body,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
        }

        // Project name meta
        if (task.projectName != null) {
            Text(
                text = task.projectName,
                style = type.meta,
                color = colors.textTertiary,
            )
        }

        // Triage chips row
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip(label = "Today", onClick = onSchedule)
            Chip(label = "Backlog", onClick = onBacklog)

            Box {
                Chip(label = "+ Project", onClick = { showPicker = true })
                DropdownMenu(
                    expanded = showPicker,
                    onDismissRequest = { showPicker = false },
                ) {
                    if (projects.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No projects", style = type.body, color = colors.textTertiary) },
                            onClick = { showPicker = false },
                        )
                    } else {
                        projects.forEach { proj ->
                            DropdownMenuItem(
                                text = { Text(proj.name, style = type.body, color = colors.textPrimary) },
                                onClick = {
                                    onAssignProject(proj.id)
                                    showPicker = false
                                },
                            )
                        }
                    }
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Delete task",
                    tint = colors.textTertiary,
                )
            }
        }
    }
}
