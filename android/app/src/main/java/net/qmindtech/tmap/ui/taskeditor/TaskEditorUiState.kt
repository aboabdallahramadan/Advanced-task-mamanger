package net.qmindtech.tmap.ui.taskeditor

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.SubtaskEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.recurrence.RecurrenceDraft
import net.qmindtech.tmap.data.recurrence.RecurrenceEndType
import net.qmindtech.tmap.data.recurrence.RecurrenceFrequency
import net.qmindtech.tmap.data.repository.TaskDraft
import net.qmindtech.tmap.data.repository.TaskEdit
import java.time.Instant
import java.time.LocalDate

data class TaskEditorUiState(
  val taskId: String? = null,
  val isEdit: Boolean = false,
  val loading: Boolean = true,
  val title: String = "",
  val notes: String = "",
  val projectId: String? = null,
  val labels: List<String> = emptyList(),
  val status: TaskStatus = TaskStatus.Planned,
  val plannedDate: LocalDate? = null,
  val scheduledStart: Instant? = null,
  val scheduledEnd: Instant? = null,
  val durationMinutes: Int? = 30,
  val actualTimeMinutes: Int = 0,
  val priority: Int? = null,
  val reminderMinutes: Int? = 0,
  val dueDate: LocalDate? = null,
  val completedAt: Instant? = null,
  val subtasks: List<SubtaskEntity> = emptyList(),
  val projects: List<ProjectEntity> = emptyList(),
  val saved: Boolean = false,
  val recurrenceEnabled: Boolean = false,
  val recurrenceFrequency: RecurrenceFrequency = RecurrenceFrequency.Daily,
  val recurrenceInterval: Int = 1,
  val recurrenceDaysOfWeek: List<Int> = listOf(0),
  val recurrenceEndType: RecurrenceEndType = RecurrenceEndType.Never,
  val recurrenceEndCount: Int = 10,
  val recurrenceEndDate: LocalDate? = null,
  val recurrenceRuleId: String? = null,
  val recurrenceDetached: Boolean = false,
)

fun TaskEntity.toEditorState(
  subtasks: List<SubtaskEntity>,
  projects: List<ProjectEntity>,
): TaskEditorUiState = TaskEditorUiState(
  taskId = id,
  isEdit = true,
  loading = false,
  title = title,
  notes = notes ?: "",
  projectId = projectId,
  labels = labels,
  status = status,
  plannedDate = plannedDate,
  scheduledStart = scheduledStart,
  scheduledEnd = scheduledEnd,
  durationMinutes = durationMinutes,
  actualTimeMinutes = actualTimeMinutes,
  priority = priority,
  reminderMinutes = reminderMinutes,
  dueDate = dueDate,
  completedAt = completedAt,
  subtasks = subtasks,
  projects = projects,
  recurrenceRuleId = recurrenceRuleId,
  recurrenceDetached = recurrenceDetached,
)

fun TaskEditorUiState.toDraft(): TaskDraft = TaskDraft(
  title = title.trim(),
  notes = notes.ifBlank { null },
  projectId = projectId,
  labels = labels,
  status = status,
  plannedDate = plannedDate,
  scheduledStart = scheduledStart,
  scheduledEnd = scheduledEnd,
  durationMinutes = durationMinutes,
  priority = priority,
  reminderMinutes = reminderMinutes,
  dueDate = dueDate,
)

fun TaskEditorUiState.toRecurrenceDraft(): RecurrenceDraft = RecurrenceDraft(
  frequency = recurrenceFrequency,
  interval = recurrenceInterval,
  daysOfWeek = recurrenceDaysOfWeek.sorted(),
  endType = recurrenceEndType,
  endCount = recurrenceEndCount,
  endDate = recurrenceEndDate,
)

fun TaskEditorUiState.toEdit(): TaskEdit = TaskEdit(
  title = title.trim(),
  notes = notes,
  projectId = projectId,
  labels = labels,
  status = status,
  plannedDate = plannedDate,
  scheduledStart = scheduledStart,
  scheduledEnd = scheduledEnd,
  durationMinutes = durationMinutes,
  actualTimeMinutes = actualTimeMinutes,
  priority = priority,
  reminderMinutes = reminderMinutes,
  dueDate = dueDate,
)
