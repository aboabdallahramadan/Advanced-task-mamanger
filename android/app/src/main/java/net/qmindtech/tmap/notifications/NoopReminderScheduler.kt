package net.qmindtech.tmap.notifications

import net.qmindtech.tmap.data.local.entities.TaskEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * No-op [ReminderScheduler] used until P7 binds the real AlarmManager-backed implementation.
 * TaskRepositoryImpl injects [ReminderScheduler]; binding it to this no-op keeps the P4 Hilt graph
 * buildable before P7 lands (reminders simply aren't armed/cancelled until then). P7 REPLACES this
 * binding with `@Binds ReminderScheduler -> AlarmReminderScheduler`.
 */
@Singleton
class NoopReminderScheduler @Inject constructor() : ReminderScheduler {
    override fun arm(task: TaskEntity) = Unit
    override fun cancel(taskId: String) = Unit
    override fun canScheduleExact(): Boolean = false
}
