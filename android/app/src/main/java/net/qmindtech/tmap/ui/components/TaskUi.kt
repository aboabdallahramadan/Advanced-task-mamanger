package net.qmindtech.tmap.ui.components

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import java.time.Instant
import java.time.ZoneId

/**
 * The UI projection of a task used by every list (FIXED cross-phase contract).
 * `projectColor` is an ARGB Long (0xFFRRGGBB) or null; `priority` is 0 when unset.
 */
data class TaskUi(
    val id: String,
    val title: String,
    val projectName: String?,
    val projectColor: Long?,
    val scheduledLabel: String?,
    val subtaskDone: Int,
    val subtaskTotal: Int,
    val priority: Int,
    val hasReminder: Boolean,
    val isDone: Boolean,
)

/**
 * Maps a TaskEntity (+ its optional project) to the UI projection.
 * Subtask counts default to 0; view-models that join subtasks pass real counts.
 */
fun TaskEntity.toUi(
    project: ProjectEntity?,
    subtaskDone: Int = 0,
    subtaskTotal: Int = 0,
    zone: ZoneId = ZoneId.systemDefault(),
): TaskUi = TaskUi(
    id = id,
    title = title,
    projectName = project?.name,
    projectColor = parseProjectColor(project?.color),
    scheduledLabel = scheduledLabel(scheduledStart, durationMinutes, zone),
    subtaskDone = subtaskDone,
    subtaskTotal = subtaskTotal,
    priority = priority ?: 0,
    hasReminder = reminderMinutes != null,
    isDone = status == TaskStatus.Done,
)

/** "9:30", "9:30–10:15" (en-dash), or null when there is no scheduled start. */
fun scheduledLabel(start: Instant?, durationMinutes: Int?, zone: ZoneId): String? {
    if (start == null) return null
    val startLocal = start.atZone(zone).toLocalTime()
    val startStr = formatTime(startLocal.hour, startLocal.minute)
    if (durationMinutes == null || durationMinutes <= 0) return startStr
    val endLocal = start.plusSeconds(durationMinutes * 60L).atZone(zone).toLocalTime()
    return "$startStr–${formatTime(endLocal.hour, endLocal.minute)}"
}

private fun formatTime(hour: Int, minute: Int): String =
    "$hour:${minute.toString().padStart(2, '0')}"

/** "#6EA8FE" / "6EA8FE" → 0xFF6EA8FEL; null/blank/malformed → null. */
fun parseProjectColor(hex: String?): Long? {
    val cleaned = hex?.trim()?.removePrefix("#") ?: return null
    if (cleaned.length != 6) return null
    val rgb = cleaned.toLongOrNull(16) ?: return null
    return 0xFF000000L or rgb
}
