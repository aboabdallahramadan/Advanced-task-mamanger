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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Bottom-sheet task editor (spec §6.3 — "Tap a card → editor opens as a bottom sheet").
 *
 * Wraps [SheetScaffold] and renders all task fields: title, notes, subtasks (add/complete/
 * rename/delete — edit mode only), project, planned date, scheduled start/end + duration,
 * due date, priority, reminder, status.  Actions: Complete, Delete, Create/Update.
 *
 * Date/time fields use Material3 [DatePickerDialog] and [TimePicker] dialogs, rendered dark
 * via the TmapTheme M3 bridge.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskEditorSheet(
    taskId: String?,
    onDismiss: () -> Unit,
    viewModel: TaskEditorViewModel = hiltViewModel(),
) {
    // When opened as a sheet the ViewModel's SavedStateHandle has no taskId injected by the
    // nav graph, so we drive loading imperatively.  load() is idempotent when called with the
    // same id and the state is already populated.
    LaunchedEffect(taskId) { viewModel.load(taskId) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current
    val spacing = LocalTmapSpacing.current
    var newSubtask by remember { mutableStateOf("") }

    // ── Dialog visibility flags ───────────────────────────────────────────
    var showPlannedDatePicker by remember { mutableStateOf(false) }
    var showDueDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.accent,
        unfocusedBorderColor = colors.borderSubtle,
        cursorColor = colors.accent,
        focusedLabelColor = colors.accent,
        unfocusedLabelColor = colors.textSecondary,
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textBody,
    )

    // ── Planned-date picker dialog ────────────────────────────────────────
    if (showPlannedDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.plannedDate
                ?.atStartOfDay(ZoneId.of("UTC"))?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPlannedDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { ms ->
                        viewModel.onPlannedDateChange(
                            Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).toLocalDate()
                        )
                    }
                    showPlannedDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPlannedDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // ── Due-date picker dialog ────────────────────────────────────────────
    if (showDueDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.dueDate
                ?.atStartOfDay(ZoneId.of("UTC"))?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDueDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { ms ->
                        viewModel.onDueDateChange(
                            Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).toLocalDate()
                        )
                    }
                    showDueDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDueDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // ── Scheduled-start time picker dialog ───────────────────────────────
    if (showStartTimePicker) {
        val initialTime = state.scheduledStart
            ?.let { LocalTime.ofInstant(it, viewModel.zone()) }
            ?: LocalTime.of(9, 0)
        val timeState = rememberTimePickerState(
            initialHour = initialTime.hour,
            initialMinute = initialTime.minute,
            is24Hour = false,
        )
        Dialog(onDismissRequest = { showStartTimePicker = false }) {
            Column {
                TimePicker(state = timeState)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { showStartTimePicker = false }) { Text("Cancel") }
                    TextButton(onClick = {
                        viewModel.onScheduledStartTimeChange(
                            LocalTime.of(timeState.hour, timeState.minute)
                        )
                        showStartTimePicker = false
                    }) { Text("OK") }
                }
            }
        }
    }

    // ── Scheduled-end time picker dialog ─────────────────────────────────
    if (showEndTimePicker) {
        val initialTime = state.scheduledEnd
            ?.let { LocalTime.ofInstant(it, viewModel.zone()) }
            ?: LocalTime.of(10, 0)
        val timeState = rememberTimePickerState(
            initialHour = initialTime.hour,
            initialMinute = initialTime.minute,
            is24Hour = false,
        )
        Dialog(onDismissRequest = { showEndTimePicker = false }) {
            Column {
                TimePicker(state = timeState)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { showEndTimePicker = false }) { Text("Cancel") }
                    TextButton(onClick = {
                        viewModel.onScheduledEndTimeChange(
                            LocalTime.of(timeState.hour, timeState.minute)
                        )
                        showEndTimePicker = false
                    }) { Text("OK") }
                }
            }
        }
    }

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

            // ── Planned date ─────────────────────────────────────────────
            SectionLabel(text = "Planned date", colors = colors, type = type)
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    label = state.plannedDate?.format(DATE_FORMATTER) ?: "No date",
                    selected = state.plannedDate != null,
                    onClick = { showPlannedDatePicker = true },
                    modifier = Modifier.semantics {
                        contentDescription = "Pick planned date, current value: ${state.plannedDate?.format(DATE_FORMATTER) ?: "none"}"
                    },
                )
                if (state.plannedDate != null) {
                    IconButton(
                        onClick = { viewModel.onPlannedDateChange(null) },
                        modifier = Modifier.semantics { contentDescription = "Clear planned date" },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                            tint = colors.textTertiary,
                        )
                    }
                }
            }

            // ── Scheduled start / end ─────────────────────────────────────
            SectionLabel(text = "Scheduled time", colors = colors, type = type)
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    label = state.scheduledStart
                        ?.let { LocalTime.ofInstant(it, viewModel.zone()).format(TIME_FORMATTER) }
                        ?: "Start time",
                    selected = state.scheduledStart != null,
                    onClick = { showStartTimePicker = true },
                    modifier = Modifier.semantics {
                        contentDescription = "Pick scheduled start time, current value: ${
                            state.scheduledStart?.let { LocalTime.ofInstant(it, viewModel.zone()).format(TIME_FORMATTER) } ?: "none"
                        }"
                    },
                )
                if (state.scheduledStart != null) {
                    IconButton(
                        onClick = { viewModel.onScheduledStartChange(null) },
                        modifier = Modifier.semantics { contentDescription = "Clear scheduled start" },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                            tint = colors.textTertiary,
                        )
                    }
                }
                Text(
                    text = "–",
                    style = type.meta,
                    color = colors.textTertiary,
                )
                FilterChip(
                    label = state.scheduledEnd
                        ?.let { LocalTime.ofInstant(it, viewModel.zone()).format(TIME_FORMATTER) }
                        ?: "End time",
                    selected = state.scheduledEnd != null,
                    onClick = { showEndTimePicker = true },
                    modifier = Modifier.semantics {
                        contentDescription = "Pick scheduled end time, current value: ${
                            state.scheduledEnd?.let { LocalTime.ofInstant(it, viewModel.zone()).format(TIME_FORMATTER) } ?: "none"
                        }"
                    },
                )
                if (state.scheduledEnd != null) {
                    IconButton(
                        onClick = { viewModel.onScheduledEndChange(null) },
                        modifier = Modifier.semantics { contentDescription = "Clear scheduled end" },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                            tint = colors.textTertiary,
                        )
                    }
                }
            }

            // ── Due date ──────────────────────────────────────────────────
            SectionLabel(text = "Due date", colors = colors, type = type)
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    label = state.dueDate?.format(DATE_FORMATTER) ?: "No due date",
                    selected = state.dueDate != null,
                    onClick = { showDueDatePicker = true },
                    modifier = Modifier.semantics {
                        contentDescription = "Pick due date, current value: ${state.dueDate?.format(DATE_FORMATTER) ?: "none"}"
                    },
                )
                if (state.dueDate != null) {
                    IconButton(
                        onClick = { viewModel.onDueDateChange(null) },
                        modifier = Modifier.semantics { contentDescription = "Clear due date" },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                            tint = colors.textTertiary,
                        )
                    }
                }
            }

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

// ── Formatters ────────────────────────────────────────────────────────────────

private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

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
