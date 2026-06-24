package net.qmindtech.tmap.data.sync

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.DailyPlanEntity
import net.qmindtech.tmap.data.local.entities.FocusSessionEntity
import net.qmindtech.tmap.data.local.entities.NoteEntity
import net.qmindtech.tmap.data.local.entities.NoteGroupEntity
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.SettingEntity
import net.qmindtech.tmap.data.local.entities.SubtaskEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.remote.dto.CreateFocusSessionRequest
import net.qmindtech.tmap.data.remote.dto.CreateNoteGroupRequest
import net.qmindtech.tmap.data.remote.dto.CreateNoteRequest
import net.qmindtech.tmap.data.remote.dto.CreateProjectRequest
import net.qmindtech.tmap.data.remote.dto.CreateSubtaskRequest
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.data.remote.dto.DailyPlanResponse
import net.qmindtech.tmap.data.remote.dto.DailyPlanSyncRow
import net.qmindtech.tmap.data.remote.dto.FocusSessionResponse
import net.qmindtech.tmap.data.remote.dto.FocusSessionSyncRow
import net.qmindtech.tmap.data.remote.dto.NoteGroupResponse
import net.qmindtech.tmap.data.remote.dto.NoteGroupSyncRow
import net.qmindtech.tmap.data.remote.dto.NoteResponse
import net.qmindtech.tmap.data.remote.dto.NoteSyncRow
import net.qmindtech.tmap.data.remote.dto.ProjectResponse
import net.qmindtech.tmap.data.remote.dto.ProjectSyncRow
import net.qmindtech.tmap.data.remote.dto.SettingSyncRow
import net.qmindtech.tmap.data.remote.dto.SubtaskResponse
import net.qmindtech.tmap.data.remote.dto.SubtaskSyncRow
import net.qmindtech.tmap.data.remote.dto.TaskResponse
import net.qmindtech.tmap.data.remote.dto.TaskSyncRow
import net.qmindtech.tmap.data.remote.dto.UpdateNoteGroupRequest
import net.qmindtech.tmap.data.remote.dto.UpdateNoteRequest
import net.qmindtech.tmap.data.remote.dto.UpdateProjectRequest
import net.qmindtech.tmap.data.remote.dto.UpdateSubtaskRequest
import net.qmindtech.tmap.data.remote.dto.UpdateTaskRequest
import net.qmindtech.tmap.data.remote.dto.UpsertDailyPlanRequest
import java.time.Instant
import java.time.LocalDate

/**
 * DTO/sync-row <-> Room-entity mapping + string<->java.time helpers.
 * Status is sent PascalCase (TaskStatus.name) and parsed case-insensitively, defaulting to
 * Inbox when unparseable (matches the desktop client's tolerant inbound parse).
 */
object Mappers {

    fun parseDate(s: String?): LocalDate? = s?.let { LocalDate.parse(it) }
    fun parseInstant(s: String?): Instant? = s?.let { Instant.parse(it) }
    fun formatDate(d: LocalDate?): String? = d?.toString()
    fun formatInstant(i: Instant?): String? = i?.toString()

    // ── Tasks ──────────────────────────────────────────────
    fun TaskResponse.toEntity(): TaskEntity = TaskEntity(
        id = id,
        title = title,
        notes = notes,
        projectId = projectId,
        labels = labels ?: emptyList(),
        source = source,
        status = TaskStatus.parse(status) ?: TaskStatus.Inbox,
        plannedDate = parseDate(plannedDate),
        scheduledStart = parseInstant(scheduledStart),
        scheduledEnd = parseInstant(scheduledEnd),
        durationMinutes = durationMinutes,
        actualTimeMinutes = actualTimeMinutes,
        priority = priority,
        reminderMinutes = reminderMinutes,
        rank = rank,
        dueDate = parseDate(dueDate),
        recurrenceRuleId = recurrenceRuleId,
        isRecurrenceTemplate = isRecurrenceTemplate,
        recurrenceDetached = recurrenceDetached,
        recurrenceOriginalDate = parseDate(recurrenceOriginalDate),
        completedAt = parseInstant(completedAt),
        createdAt = parseInstant(createdAt)!!,
        updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq,
    )

    fun TaskSyncRow.toEntity(): TaskEntity = TaskEntity(
        id = id,
        title = title,
        notes = notes,
        projectId = projectId,
        labels = labels ?: emptyList(),
        source = source,
        status = TaskStatus.parse(status) ?: TaskStatus.Inbox,
        plannedDate = parseDate(plannedDate),
        scheduledStart = parseInstant(scheduledStart),
        scheduledEnd = parseInstant(scheduledEnd),
        durationMinutes = durationMinutes,
        actualTimeMinutes = actualTimeMinutes,
        priority = priority,
        reminderMinutes = reminderMinutes,
        rank = rank,
        dueDate = parseDate(dueDate),
        recurrenceRuleId = recurrenceRuleId,
        isRecurrenceTemplate = isRecurrenceTemplate,
        recurrenceDetached = recurrenceDetached,
        recurrenceOriginalDate = parseDate(recurrenceOriginalDate),
        completedAt = parseInstant(completedAt),
        createdAt = parseInstant(createdAt)!!,
        updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq,
    )

    fun TaskEntity.toCreateRequest(): CreateTaskRequest = CreateTaskRequest(
        id = id,
        title = title,
        notes = notes,
        projectId = projectId,
        labels = labels,
        source = source ?: "android",
        status = status.name,
        plannedDate = formatDate(plannedDate),
        scheduledStart = formatInstant(scheduledStart),
        scheduledEnd = formatInstant(scheduledEnd),
        durationMinutes = durationMinutes,
        priority = priority,
        reminderMinutes = reminderMinutes,
        rank = rank,
        dueDate = formatDate(dueDate),
    )

    fun TaskEntity.toUpdateRequest(): UpdateTaskRequest = UpdateTaskRequest(
        title = title,
        notes = notes,
        projectId = projectId,
        labels = labels,
        source = source,
        status = status.name,
        plannedDate = formatDate(plannedDate),
        scheduledStart = formatInstant(scheduledStart),
        scheduledEnd = formatInstant(scheduledEnd),
        durationMinutes = durationMinutes,
        priority = priority,
        reminderMinutes = reminderMinutes,
        rank = rank,
        dueDate = formatDate(dueDate),
    )

    // ── Subtasks ───────────────────────────────────────────
    fun SubtaskResponse.toEntity(changeSeq: Long = 0L): SubtaskEntity = SubtaskEntity(
        id = id,
        taskId = taskId,
        title = title,
        completed = completed,
        sortOrder = sortOrder,
        createdAt = parseInstant(createdAt)!!,
        updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq,
    )

    fun SubtaskSyncRow.toEntity(): SubtaskEntity = SubtaskEntity(
        id = id,
        taskId = taskId,
        title = title,
        completed = completed,
        sortOrder = sortOrder,
        createdAt = parseInstant(createdAt)!!,
        updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq,
    )

    fun SubtaskEntity.toCreateRequest(): CreateSubtaskRequest = CreateSubtaskRequest(id = id, title = title)

    fun SubtaskEntity.toUpdateRequest(): UpdateSubtaskRequest =
        UpdateSubtaskRequest(title = title, completed = completed, sortOrder = sortOrder)

    // ── Projects ───────────────────────────────────────────
    fun ProjectResponse.toEntity(changeSeq: Long = 0L): ProjectEntity = ProjectEntity(
        id = id,
        name = name,
        color = color,
        emoji = emoji,
        rank = rank,
        actualTimeMinutes = actualTimeMinutes,
        createdAt = parseInstant(createdAt)!!,
        updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq,
    )

    fun ProjectSyncRow.toEntity(): ProjectEntity = ProjectEntity(
        id = id,
        name = name,
        color = color,
        emoji = emoji,
        rank = rank,
        actualTimeMinutes = actualTimeMinutes,
        createdAt = parseInstant(createdAt)!!,
        updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq,
    )

    fun ProjectEntity.toCreateRequest(): CreateProjectRequest =
        CreateProjectRequest(id = id, name = name, color = color, emoji = emoji, rank = rank)

    fun ProjectEntity.toUpdateRequest(): UpdateProjectRequest =
        UpdateProjectRequest(name = name, color = color, emoji = emoji, rank = rank)

    // ── Settings ───────────────────────────────────────────
    fun SettingSyncRow.toEntity(): SettingEntity = SettingEntity(key = key, value = value, changeSeq = changeSeq)

    // ── Notes ──────────────────────────────────────────────
    fun NoteResponse.toEntity(changeSeq: Long = 0L, pinnedAt: Instant? = null): NoteEntity = NoteEntity(
        id = id,
        groupId = groupId,
        projectId = projectId,
        title = title,
        content = content,
        rank = rank,
        createdAt = parseInstant(createdAt)!!,
        updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq,
        deletedAt = null,
        pinnedAt = pinnedAt,
    )

    fun NoteSyncRow.toEntity(): NoteEntity = NoteEntity(
        id = id,
        groupId = groupId,
        projectId = projectId,
        title = title,
        content = content,
        rank = rank,
        createdAt = parseInstant(createdAt)!!,
        updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq,
        deletedAt = parseInstant(deletedAt),
        pinnedAt = null, // local-only; PullRunner preserves any existing local pin on upsert
    )

    fun NoteEntity.toCreateRequest(): CreateNoteRequest = CreateNoteRequest(
        id = id, groupId = groupId, projectId = projectId, title = title, content = content, rank = rank,
    )

    fun NoteEntity.toUpdateRequest(): UpdateNoteRequest = UpdateNoteRequest(
        groupId = groupId, projectId = projectId, title = title, content = content, rank = rank,
    )

    // ── Note-groups ────────────────────────────────────────
    fun NoteGroupResponse.toEntity(changeSeq: Long = 0L): NoteGroupEntity = NoteGroupEntity(
        id = id, name = name, emoji = emoji, projectId = projectId, rank = rank,
        createdAt = parseInstant(createdAt)!!, updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq, deletedAt = null,
    )

    fun NoteGroupSyncRow.toEntity(): NoteGroupEntity = NoteGroupEntity(
        id = id, name = name, emoji = emoji, projectId = projectId, rank = rank,
        createdAt = parseInstant(createdAt)!!, updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq, deletedAt = parseInstant(deletedAt),
    )

    fun NoteGroupEntity.toCreateRequest(): CreateNoteGroupRequest =
        CreateNoteGroupRequest(id = id, name = name, emoji = emoji, projectId = projectId, rank = rank)

    fun NoteGroupEntity.toUpdateRequest(): UpdateNoteGroupRequest =
        UpdateNoteGroupRequest(name = name, emoji = emoji, projectId = projectId, rank = rank)

    // ── Focus-sessions ─────────────────────────────────────
    fun FocusSessionResponse.toEntity(changeSeq: Long = 0L): FocusSessionEntity = FocusSessionEntity(
        id = id, taskId = taskId, project = project,
        startedAt = parseInstant(startedAt)!!, endedAt = parseInstant(endedAt)!!,
        minutes = minutes, date = parseDate(date)!!,
        createdAt = parseInstant(createdAt)!!, updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq, deletedAt = null,
    )

    fun FocusSessionSyncRow.toEntity(): FocusSessionEntity = FocusSessionEntity(
        id = id, taskId = taskId, project = project,
        startedAt = parseInstant(startedAt)!!, endedAt = parseInstant(endedAt)!!,
        minutes = minutes, date = parseDate(date)!!,
        createdAt = parseInstant(createdAt)!!, updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq, deletedAt = parseInstant(deletedAt),
    )

    fun FocusSessionEntity.toCreateRequest(): CreateFocusSessionRequest = CreateFocusSessionRequest(
        id = id, taskId = taskId, project = project,
        startedAt = formatInstant(startedAt)!!, endedAt = formatInstant(endedAt)!!,
        minutes = minutes, date = formatDate(date)!!,
    )

    // ── Daily-plans (date-keyed) ───────────────────────────
    fun DailyPlanResponse.toEntity(changeSeq: Long = 0L): DailyPlanEntity = DailyPlanEntity(
        date = parseDate(date)!!, committedAt = parseInstant(committedAt)!!,
        plannedTaskIds = plannedTaskIds, plannedMinutes = plannedMinutes,
        changeSeq = changeSeq, deletedAt = null,
    )

    fun DailyPlanSyncRow.toEntity(): DailyPlanEntity = DailyPlanEntity(
        date = parseDate(date)!!, committedAt = parseInstant(committedAt)!!,
        plannedTaskIds = plannedTaskIds, plannedMinutes = plannedMinutes,
        changeSeq = changeSeq, deletedAt = parseInstant(deletedAt),
    )

    fun DailyPlanEntity.toUpsertRequest(): UpsertDailyPlanRequest = UpsertDailyPlanRequest(
        plannedTaskIds = plannedTaskIds, plannedMinutes = plannedMinutes,
    )
}
