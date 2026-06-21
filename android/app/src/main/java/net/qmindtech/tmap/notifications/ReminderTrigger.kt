package net.qmindtech.tmap.notifications

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Bare-date reminders (plannedDate/dueDate with no scheduledStart) anchor to 09:00 local. */
const val DEFAULT_REMINDER_HOUR = 9

/**
 * Pure trigger-time computation (no Android types) so it is unit-testable without shadows.
 *
 * Priority:
 *  1. scheduledStart present  -> scheduledStart - reminderMinutes   (requires reminderMinutes != null)
 *  2. plannedDate present     -> plannedDate@09:00 local - reminderMinutes (requires reminderMinutes != null)
 *  3. dueDate present         -> dueDate@09:00 local  (a bare due date fires at 9am; no minute offset)
 *
 * Returns null when the task can never fire: done/archived (or completedAt set), or no time anchor,
 * or a timed anchor with no reminderMinutes intent. Past-trigger filtering belongs to the caller.
 */
object ReminderTrigger {

    fun computeTriggerAt(task: TaskEntity, zone: ZoneId): Instant? {
        if (task.completedAt != null) return null
        if (task.status == TaskStatus.Done || task.status == TaskStatus.Archived) return null

        val nineLocal = LocalTime.of(DEFAULT_REMINDER_HOUR, 0)
        val minutes = task.reminderMinutes

        task.scheduledStart?.let { start ->
            if (minutes == null) return null
            return start.minusSeconds(minutes.toLong() * 60L)
        }
        task.plannedDate?.let { date ->
            if (minutes == null) return null
            val anchor = date.atTime(nineLocal).atZone(zone).toInstant()
            return anchor.minusSeconds(minutes.toLong() * 60L)
        }
        task.dueDate?.let { date ->
            return date.atTime(nineLocal).atZone(zone).toInstant()
        }
        return null
    }
}
