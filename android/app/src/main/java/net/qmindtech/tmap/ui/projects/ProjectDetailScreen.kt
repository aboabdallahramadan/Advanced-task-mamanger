package net.qmindtech.tmap.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChecklistRtl
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.ProgressRing
import net.qmindtech.tmap.ui.components.SectionLabel
import net.qmindtech.tmap.ui.components.TaskCard
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapType

/**
 * Project detail screen — header (back, emoji+name, ProgressRing) + TaskCard list.
 *
 * Edit: opens [ProjectEditDialog] pre-filled; delegates to [ProjectDetailViewModel.update].
 * Delete: shows a confirm dialog (danger color); calls [ProjectDetailViewModel.delete] then [onBack].
 *
 * The [onBack] lambda is also wired to the back icon (RTL-mirrored) in the header.
 */
@Composable
fun ProjectDetailScreen(
    onBack: () -> Unit,
    onOpenTask: (taskId: String) -> Unit,
    viewModel: ProjectDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current

    // Dialog state
    var editOpen by remember { mutableStateOf(false) }
    var deleteConfirmOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

        // ── Header: back · emoji · name · ProgressRing · edit · delete ──────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textPrimary,
                )
            }

            if (state.project?.emoji?.isNotBlank() == true) {
                Text(text = state.project!!.emoji, style = MaterialTheme.typography.titleMedium)
            }

            Text(
                text = state.project?.name ?: "Project",
                modifier = Modifier.weight(1f),
                color = colors.textPrimary,
                style = type.heading,
                maxLines = 1,
            )

            ProgressRing(progress = state.progress, modifier = Modifier.size(48.dp)) {
                Text(
                    text = "${state.done}/${state.total}",
                    color = colors.textSecondary,
                    style = type.meta,
                )
            }

            IconButton(onClick = { editOpen = true }) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Edit project",
                    tint = colors.textSecondary,
                )
            }

            IconButton(onClick = { deleteConfirmOpen = true }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete project",
                    tint = colors.danger,
                )
            }
        }

        // ── Task list / empty state ────────────────────────────────────────────
        if (!state.loading && state.items.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.ChecklistRtl,
                title = "No tasks in this project",
                subtitle = "Add tasks and assign them here.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = "tasks-label") {
                    SectionLabel("Tasks", modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                }
                items(state.items, key = { it.task.id }) { item ->
                    TaskCard(
                        task = item.ui,
                        onToggleComplete = { viewModel.toggleDone(item.task) },
                        onClick = { onOpenTask(item.task.id) },
                    )
                }
                // P4-NOTES-SLOT: P4 (Notes UI) will render this project's notes here —
                // a "Notes" SectionLabel + NoteCard list backed by NoteRepository.observeAll(projectId).
                // Intentionally empty until the Notes domain (P3 data + P4 UI) lands; do not wire before then.
                item(key = "p4-notes-slot") { /* P4-NOTES-SLOT reserved */ }
            }
        }
    }

    // ── Edit dialog ────────────────────────────────────────────────────────────
    if (editOpen) {
        ProjectEditDialog(
            initial = state.project,
            onDismiss = { editOpen = false },
            onSave = { name, color, emoji ->
                viewModel.update(name, color, emoji)
                editOpen = false
            },
        )
    }

    // ── Delete confirm dialog ─────────────────────────────────────────────────
    if (deleteConfirmOpen) {
        ProjectEditDialog(
            initial = state.project,
            onDismiss = { deleteConfirmOpen = false },
            onSave = { _, _, _ -> deleteConfirmOpen = false },
            onDelete = {
                viewModel.delete()
                deleteConfirmOpen = false
                onBack()
            },
        )
    }
}
