package net.qmindtech.tmap.notifications

import net.qmindtech.tmap.data.local.entities.TaskEntity

/**
 * Arms/cancels a per-task exact reminder alarm. P7 owns the concrete AlarmManager-backed
 * implementation (notifications/ReminderScheduler -> a class implementing this interface). It is
 * declared as an interface here so TaskRepositoryImpl can depend on the seam and be unit-tested
 * with a recording fake before P7 lands. P7 must keep these exact members.
 */
interface ReminderScheduler {
    /** Compute the trigger and set an exact alarm; no-op if past/none/done/deleted. */
    fun arm(task: TaskEntity)

    /** Cancel the pending alarm for this task id. */
    fun cancel(taskId: String)

    /** Whether the platform currently permits exact alarms (Android 12+ policy). */
    fun canScheduleExact(): Boolean
}
