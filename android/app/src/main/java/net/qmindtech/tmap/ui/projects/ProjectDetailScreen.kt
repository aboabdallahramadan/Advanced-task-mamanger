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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.ProgressRing
import net.qmindtech.tmap.ui.components.SectionLabel
import net.qmindtech.tmap.ui.components.TaskCard
import net.qmindtech.tmap.ui.notes.NoteCard
import net.qmindtech.tmap.ui.notes.NoteCardUi
import net.qmindtech.tmap.ui.notes.noteSnippet
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
    onOpenNote: (noteId: String) -> Unit,
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
                Text(text = state.project!!.emoji, style = type.heading)
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
                // P4.7: project's notes section — NoteCard list via NoteRepository.observeAll(null, projectId).
                item(key = "notes-section") {
                    ProjectNotesSection(
                        notes = state.notes.map { note ->
                            NoteCardUi(
                                id = note.id,
                                title = note.title.ifBlank { "Untitled" },
                                snippet = noteSnippet(note.content),
                                projectColor = null,
                                projectName = null,
                                updatedAt = note.updatedAt,
                                pinned = note.pinnedAt != null,
                            )
                        },
                        onOpenNote = onOpenNote,
                    )
                }
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
        AlertDialog(
            onDismissRequest = { deleteConfirmOpen = false },
            containerColor = colors.surfaceRaised,
            title = {
                Text("Delete project?", style = type.heading, color = colors.textPrimary)
            },
            text = {
                Text("Delete this project? Its tasks will be kept.", color = colors.textSecondary)
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete()
                    deleteConfirmOpen = false
                    onBack()
                }) {
                    Text("Delete", color = colors.danger, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmOpen = false }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ProjectNotesSection — P4.7 hook (brief §6.8/§6.10)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Renders this project's notes below the task list inside [ProjectDetailScreen].
 *
 * Shows a "Notes" [SectionLabel], then either a [NoteCard] per note or an empty-state
 * text message when [notes] is empty. Card tap calls [onOpenNote] with the note id —
 * which routes to [NoteEditorSheet] via SheetHost / SheetCommands.
 *
 * [notes] are pre-mapped to [NoteCardUi] by the caller so this composable remains stateless
 * (data flows through [ProjectDetailViewModel.uiState.notes]).
 */
@Composable
fun ProjectNotesSection(
    notes: List<NoteCardUi>,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current

    Column(modifier = modifier.fillMaxWidth()) {
        SectionLabel("Notes", modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        if (notes.isEmpty()) {
            Text(
                text = "No notes for this project yet.",
                style = type.meta,
                color = colors.textTertiary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                notes.forEach { note ->
                    NoteCard(
                        note = note,
                        onClick = { onOpenNote(note.id) },
                        onTogglePin = {},
                    )
                }
            }
        }
    }
}
