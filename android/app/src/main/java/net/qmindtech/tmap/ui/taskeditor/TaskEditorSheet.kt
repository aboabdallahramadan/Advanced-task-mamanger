package net.qmindtech.tmap.ui.taskeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.ui.components.FilterChip
import net.qmindtech.tmap.ui.components.PrimaryButton
import net.qmindtech.tmap.ui.components.PriorityDisplay
import net.qmindtech.tmap.ui.components.SecondaryButton
import net.qmindtech.tmap.ui.components.SheetScaffold
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing
import net.qmindtech.tmap.ui.theme.LocalTmapType

/**
 * Bottom-sheet task editor (spec §6.3 — "Tap a card → editor opens as a bottom sheet").
 *
 * Wraps [SheetScaffold] and renders all task fields: title, notes, subtasks (add/complete/
 * rename/delete — edit mode only), project, planned date (gated — see note below), scheduled
 * start/end + duration, due date (gated), priority, reminder, status.  Actions: Complete, Delete,
 * Create/Update.
 *
 * NOTE on date pickers: Planned-date and due-date use the platform [android.app.DatePickerDialog].
 * A composable wrapper is not yet componentized (P0 follow-up).  Until then, date fields are
 * present in the UiState and wired to their VM actions; the tap targets are implemented as
 * chip-like buttons that will open the dialog in a future task.
 *
 * Subtask drag-to-reorder requires a drag-handle in [SubtaskRow] (P0/follow-up); the VM action
 * [TaskEditorViewModel.reorderSubtasks] exists and will be wired then.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskEditorSheet(
    onDismiss: () -> Unit,
    viewModel: TaskEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current
    val spacing = LocalTmapSpacing.current
    var newSubtask by remember { mutableStateOf("") }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.accent,
        unfocusedBorderColor = colors.borderSubtle,
        cursorColor = colors.accent,
        focusedLabelColor = colors.accent,
        unfocusedLabelColor = colors.textSecondary,
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textBody,
    )

    SheetScaffold(
        onDismiss = onDismiss,
        title = if (state.isEdit) "Edit Task" else "New Task",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // ── Title ────────────────────────────────────────────────────
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Title") },
                singleLine = true,
                colors = textFieldColors,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )

            // ── Notes ────────────────────────────────────────────────────
            OutlinedTextField(
                value = state.notes,
                onValueChange = viewModel::onNotesChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Notes") },
                minLines = 2,
                maxLines = 5,
                colors = textFieldColors,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )

            // ── Status ───────────────────────────────────────────────────
            SectionLabel(text = "Status", colors = colors, type = type)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.base),
            ) {
                listOf(
                    TaskStatus.Inbox,
                    TaskStatus.Backlog,
                    TaskStatus.Planned,
                    TaskStatus.Scheduled,
                    TaskStatus.Done,
                ).forEach { st ->
                    FilterChip(
                        label = st.name,
                        selected = state.status == st,
                        onClick = { viewModel.onStatusChange(st) },
                    )
                }
            }

            // ── Priority ─────────────────────────────────────────────────
            SectionLabel(text = "Priority", colors = colors, type = type)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.base),
            ) {
                FilterChip(
                    label = "None",
                    selected = state.priority == null,
                    onClick = { viewModel.onPriorityChange(null) },
                )
                listOf(1, 2, 3, 4).forEach { p ->
                    FilterChip(
                        label = PriorityDisplay.label(p),
                        selected = state.priority == p,
                        onClick = { viewModel.onPriorityChange(p) },
                    )
                }
            }

            // ── Project ──────────────────────────────────────────────────
            SectionLabel(text = "Project", colors = colors, type = type)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.base),
            ) {
                FilterChip(
                    label = "No project",
                    selected = state.projectId == null,
                    onClick = { viewModel.onProjectChange(null) },
                )
                state.projects.forEach { p ->
                    FilterChip(
                        label = "${p.emoji} ${p.name}",
                        selected = state.projectId == p.id,
                        onClick = { viewModel.onProjectChange(p.id) },
                    )
                }
            }

            // ── Reminder ─────────────────────────────────────────────────
            SectionLabel(text = "Reminder", colors = colors, type = type)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.base),
            ) {
                listOf(
                    null to "None",
                    0 to "At start",
                    5 to "5 min",
                    10 to "10 min",
                    15 to "15 min",
                    30 to "30 min",
                ).forEach { (m, chipLabel) ->
                    FilterChip(
                        label = chipLabel,
                        selected = state.reminderMinutes == m,
                        onClick = { viewModel.onReminderChange(m) },
                    )
                }
            }

            // ── Duration / Schedule ──────────────────────────────────────
            SectionLabel(text = "Duration", colors = colors, type = type)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.base),
            ) {
                listOf(
                    null to "No duration",
                    15 to "15 min",
                    30 to "30 min",
                    45 to "45 min",
                    60 to "1 hr",
                    90 to "1.5 hr",
                    120 to "2 hr",
                ).forEach { (m, chipLabel) ->
                    FilterChip(
                        label = chipLabel,
                        selected = state.durationMinutes == m,
                        onClick = { viewModel.onDurationChange(m) },
                    )
                }
            }

            // ── Planned date (date-picker placeholder) ───────────────────
            SectionLabel(text = "Planned date", colors = colors, type = type)
            FilterChip(
                label = state.plannedDate?.toString() ?: "No date",
                selected = state.plannedDate != null,
                // TODO(P0-followup): open DatePickerDialog — componentize date picker
                onClick = { /* date picker not yet componentized */ },
                modifier = Modifier.semantics {
                    contentDescription = "Pick planned date"
                },
            )

            // ── Due date (date-picker placeholder) ───────────────────────
            SectionLabel(text = "Due date", colors = colors, type = type)
            FilterChip(
                label = state.dueDate?.toString() ?: "No due date",
                selected = state.dueDate != null,
                // TODO(P0-followup): open DatePickerDialog — componentize date picker
                onClick = { /* date picker not yet componentized */ },
                modifier = Modifier.semantics {
                    contentDescription = "Pick due date"
                },
            )

            // ── Subtasks (edit mode only) ─────────────────────────────────
            if (state.isEdit) {
                SectionLabel(text = "Subtasks", colors = colors, type = type)
                state.subtasks.forEach { sub ->
                    SubtaskRow(
                        subtask = sub,
                        onToggle = { viewModel.toggleSubtask(sub) },
                        onRename = { viewModel.renameSubtask(sub, it) },
                        onDelete = { viewModel.deleteSubtask(sub.id) },
                    )
                }

                // Add-subtask row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    OutlinedTextField(
                        value = newSubtask,
                        onValueChange = { newSubtask = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("New subtask") },
                        singleLine = true,
                        colors = textFieldColors,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            viewModel.addSubtask(newSubtask)
                            newSubtask = ""
                        }),
                    )
                    IconButton(
                        onClick = {
                            viewModel.addSubtask(newSubtask)
                            newSubtask = ""
                        },
                        enabled = newSubtask.isNotBlank(),
                        modifier = Modifier.semantics {
                            contentDescription = "Add subtask"
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null, // described by parent semantics
                            tint = if (newSubtask.isNotBlank()) colors.accent else colors.textTertiary,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.sm))

            // ── Action buttons ────────────────────────────────────────────
            if (state.isEdit) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    // Complete
                    SecondaryButton(
                        text = "Complete",
                        onClick = {
                            viewModel.markDone()
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "Mark task complete" },
                    )
                    // Delete
                    IconButton(
                        onClick = { viewModel.delete(onDismiss) },
                        modifier = Modifier.semantics { contentDescription = "Delete task" },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null, // described by parent semantics
                            tint = colors.danger,
                        )
                    }
                }
            }

            PrimaryButton(
                text = if (state.isEdit) "Update" else "Create",
                onClick = { viewModel.save(onDismiss) },
                enabled = state.title.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = if (state.isEdit) "Update task" else "Create task" },
            )

            Spacer(modifier = Modifier.height(spacing.base))
        }
    }
}

// ── Local helper ──────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(
    text: String,
    colors: net.qmindtech.tmap.ui.theme.TmapColorScheme,
    type: net.qmindtech.tmap.ui.theme.TmapType,
) {
    Text(
        text = text.uppercase(),
        style = type.label,
        color = colors.textTertiary,
    )
}
