package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateTaskRequest(
    val id: String? = null,
    val title: String,
    val notes: String? = null,
    val projectId: String? = null,
    val labels: List<String>? = null,
    val source: String? = "android",
    val status: String? = null,
    val plannedDate: String? = null,
    val scheduledStart: String? = null,
    val scheduledEnd: String? = null,
    val durationMinutes: Int? = null,
    val priority: Int? = null,
    val reminderMinutes: Int? = null,
    val rank: String? = null,
    val dueDate: String? = null,
)

@Serializable
data class UpdateTaskRequest(
    val title: String? = null,
    val notes: String? = null,
    val projectId: String? = null,
    val labels: List<String>? = null,
    val source: String? = null,
    val status: String? = null,
    val plannedDate: String? = null,
    val scheduledStart: String? = null,
    val scheduledEnd: String? = null,
    val durationMinutes: Int? = null,
    val priority: Int? = null,
    val reminderMinutes: Int? = null,
    val rank: String? = null,
    val dueDate: String? = null,
)

@Serializable
data class TaskResponse(
    val id: String,
    val title: String,
    val notes: String?,
    val projectId: String?,
    val labels: List<String>? = null,
    val source: String?,
    val status: String,
    val plannedDate: String?,
    val scheduledStart: String?,
    val scheduledEnd: String?,
    val durationMinutes: Int?,
    val actualTimeMinutes: Int,
    val priority: Int?,
    val reminderMinutes: Int?,
    val rank: String?,
    val dueDate: String?,
    val recurrenceRuleId: String?,
    val isRecurrenceTemplate: Boolean,
    val recurrenceDetached: Boolean,
    val recurrenceOriginalDate: String?,
    val completedAt: String?,
    val createdAt: String,
    val updatedAt: String,
    val changeSeq: Long,
    val subtasks: List<SubtaskResponse> = emptyList(),
)
