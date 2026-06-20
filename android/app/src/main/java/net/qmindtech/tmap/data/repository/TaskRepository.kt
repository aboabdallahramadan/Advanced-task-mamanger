package net.qmindtech.tmap.data.repository

import net.qmindtech.tmap.data.local.TaskStatus
import java.time.Instant
import java.time.LocalDate

/**
 * Create/edit shapes consumed by the TaskEditor (P6) and TaskRepository.create/update.
 * Pinned verbatim from the spine CONTRACTS — do NOT alter field names/types/defaults.
 */
data class TaskDraft(
    val title: String,
    val notes: String? = null,
    val projectId: String? = null,
    val labels: List<String> = emptyList(),
    val status: TaskStatus = TaskStatus.Inbox,
    val plannedDate: LocalDate? = null,
    val scheduledStart: Instant? = null,
    val scheduledEnd: Instant? = null,
    val durationMinutes: Int? = null,
    val priority: Int? = null,
    val reminderMinutes: Int? = null,
    val dueDate: LocalDate? = null,
)

data class TaskEdit(
    val title: String? = null,
    val notes: String? = null,
    val projectId: String? = null,
    val labels: List<String>? = null,
    val status: TaskStatus? = null,
    val plannedDate: LocalDate? = null,
    val scheduledStart: Instant? = null,
    val scheduledEnd: Instant? = null,
    val durationMinutes: Int? = null,
    val priority: Int? = null,
    val reminderMinutes: Int? = null,
    val dueDate: LocalDate? = null,
    val actualTimeMinutes: Int? = null,
)
