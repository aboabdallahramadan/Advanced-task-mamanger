package net.qmindtech.tmap.notifications

import kotlinx.coroutines.flow.first
import net.qmindtech.tmap.data.local.dao.TaskDao
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.sync.SyncReminderRearmer
import javax.inject.Inject

/**
 * Diff-driven re-armer (spec §6 "sync coupling"). Implements the main-source [SyncReminderRearmer]
 * (P3) the PullRunner calls after each pull, and adds [rearmAll] for BootReceiver (P7.7).
 *
 *  - [reconcile]: cancel each deleted id's pending alarm, then arm each changed task. The scheduler
 *    internally no-ops the ones that should not fire (done / past / no anchor), so reconcile arms
 *    unconditionally and lets the scheduler decide.
 *  - [rearmAll]: arm every non-template task currently in the store ([TaskDao.observeAll] excludes
 *    recurrence templates). Used to restore alarms after a device reboot.
 *
 * Depends on the [ReminderScheduler] seam (the arm/cancel surface; AlarmReminderScheduler is the
 * real impl), so it is unit-testable with a recording fake and an in-memory Room database.
 */
class ReminderRearmer @Inject constructor(
    private val reminderScheduler: ReminderScheduler,
    private val taskDao: TaskDao,
) : SyncReminderRearmer {

    override suspend fun reconcile(changed: List<TaskEntity>, deletedIds: List<String>) {
        deletedIds.forEach { reminderScheduler.cancel(it) }
        changed.forEach { reminderScheduler.arm(it) }
    }

    suspend fun rearmAll() {
        taskDao.observeAll().first().forEach { reminderScheduler.arm(it) }
    }
}
