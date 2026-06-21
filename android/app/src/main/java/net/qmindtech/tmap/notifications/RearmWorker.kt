package net.qmindtech.tmap.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** One-shot worker that re-arms every task's reminder after reboot (or any explicit re-arm). */
@HiltWorker
class RearmWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val rearmer: ReminderRearmer,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            rearmer.rearmAll()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "rearm_reminders"
    }
}
