package net.qmindtech.tmap.ui.planning

import net.qmindtech.tmap.data.local.entities.SettingEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity

/** Setting key for the user's workday length in minutes (spec §6.4 capacity target). */
const val KEY_WORKDAY_MINUTES = "workdayMinutes"

/** Default workday capacity when the setting is missing/non-numeric: 6 hours. */
const val DEFAULT_WORKDAY_MINUTES = 360

/** Fallback planned minutes for a task that has no durationMinutes set. */
const val DEFAULT_TASK_MINUTES = 30

/** Reads KEY_WORKDAY_MINUTES from settings rows; default DEFAULT_WORKDAY_MINUTES; clamps to >= 0. */
fun workdayMinutes(settings: List<SettingEntity>): Int {
    val raw = settings.firstOrNull { it.key == KEY_WORKDAY_MINUTES }?.value
    return (raw?.toIntOrNull() ?: DEFAULT_WORKDAY_MINUTES).coerceAtLeast(0)
}

/** Planned minutes a single task contributes: its durationMinutes, else DEFAULT_TASK_MINUTES (>=0). */
fun taskMinutes(task: TaskEntity): Int =
    (task.durationMinutes ?: DEFAULT_TASK_MINUTES).coerceAtLeast(0)

/** Sum of taskMinutes over the given tasks (the live "≈ Xh planned" figure). */
fun capacityOf(tasks: List<TaskEntity>): Int = tasks.sumOf { taskMinutes(it) }

/** Fraction planned of the workday, clamped to 0f..1f (0 when capacity is 0). */
fun capacityFraction(plannedMinutes: Int, workdayMinutes: Int): Float {
    if (workdayMinutes <= 0) return 0f
    return (plannedMinutes.toFloat() / workdayMinutes.toFloat()).coerceIn(0f, 1f)
}
