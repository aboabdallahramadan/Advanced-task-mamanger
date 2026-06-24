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
    val notes: List<NoteSyncRow> = emptyList(),
    val noteGroups: List<NoteGroupSyncRow> = emptyList(),
    val focusSessions: List<FocusSessionSyncRow> = emptyList(),
    val dailyPlans: List<DailyPlanSyncRow> = emptyList(),
    val recurrenceRules: List<RecurrenceRuleSyncRow> = emptyList(),
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

@Serializable
data class NoteSyncRow(
    val id: String,
    val groupId: String?,
    val projectId: String?,
    val title: String,
    val content: String,
    val rank: String?,
    val createdAt: String,
    val updatedAt: String,
    val changeSeq: Long,
    val deletedAt: String? = null,
)

@Serializable
data class NoteGroupSyncRow(
    val id: String,
    val name: String,
    val emoji: String,
    val projectId: String?,
    val rank: String?,
    val createdAt: String,
    val updatedAt: String,
    val changeSeq: Long,
    val deletedAt: String? = null,
)

@Serializable
data class FocusSessionSyncRow(
    val id: String,
    val taskId: String?,
    val project: String,
    val startedAt: String,
    val endedAt: String,
    val minutes: Int,
    val date: String,
    val createdAt: String,
    val updatedAt: String,
    val changeSeq: Long,
    val deletedAt: String? = null,
)

@Serializable
data class DailyPlanSyncRow(
    val date: String,
    val committedAt: String,
    val plannedTaskIds: List<String> = emptyList(),
    val plannedMinutes: Int,
    val changeSeq: Long,
    val deletedAt: String? = null,
)

/** Tolerated-only (spec §7.5): modeled minimally so the /sync payload deserializes cleanly. */
@Serializable
data class RecurrenceRuleSyncRow(
    val id: String,
    val changeSeq: Long,
    val deletedAt: String? = null,
)
