package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class SyncResponse(
    val changes: SyncChanges,
    val nextSince: Long,
    val hasMore: Boolean,
    val fullResyncRequired: Boolean = false,
)

@Serializable
data class SyncChanges(
    val tasks: List<TaskSyncRow> = emptyList(),
    val subtasks: List<SubtaskSyncRow> = emptyList(),
    val projects: List<ProjectSyncRow> = emptyList(),
    val settings: List<SettingSyncRow> = emptyList(),
)

@Serializable
data class TaskSyncRow(
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
    val deletedAt: String? = null,
)

@Serializable
data class SubtaskSyncRow(
    val id: String,
    val taskId: String,
    val title: String,
    val completed: Boolean,
    val sortOrder: Int,
    val createdAt: String,
    val updatedAt: String,
    val changeSeq: Long,
    val deletedAt: String? = null,
)

@Serializable
data class ProjectSyncRow(
    val id: String,
    val name: String,
    val color: String,
    val emoji: String,
    val rank: String,
    val actualTimeMinutes: Int,
    val createdAt: String,
    val updatedAt: String,
    val changeSeq: Long,
    val deletedAt: String? = null,
)

@Serializable
data class SettingSyncRow(
    val key: String,
    val value: String,
    val changeSeq: Long,
    val deletedAt: String? = null,
)
