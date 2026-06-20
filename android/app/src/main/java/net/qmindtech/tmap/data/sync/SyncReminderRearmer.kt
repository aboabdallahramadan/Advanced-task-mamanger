package net.qmindtech.tmap.data.sync

import net.qmindtech.tmap.data.local.entities.TaskEntity

/**
 * The minimal reminder hook the sync engine needs: after each pull, reconcile alarms for the
 * tasks that changed and cancel alarms for the tasks that were tombstoned.
 *
 * This interface lives in MAIN source so PullRunner (main source) can reference it and P3 can
 * compile + test before P7 lands. P7's concrete `notifications/ReminderRearmer.kt` IMPLEMENTS
 * this interface (and additionally exposes `rearmAll()`); the Hilt @Binds in P7 binds the
 * concrete implementation to this type. The P3 tests use `FakeRearmer` (in SyncTestSupport.kt).
 */
interface SyncReminderRearmer {
    suspend fun reconcile(changed: List<TaskEntity>, deletedIds: List<String>)
}
