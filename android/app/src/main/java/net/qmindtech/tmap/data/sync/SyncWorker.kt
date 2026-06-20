package net.qmindtech.tmap.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

const val SYNC_REASON_KEY = "reason"

/**
 * The single WorkManager worker: runs ONE sync cycle by delegating to SyncEngine.syncNow(reason).
 * A thrown failure (network/unexpected) → Result.retry() so WorkManager's backoff re-runs it; a
 * completed cycle → Result.success() (per-op park/reject is surfaced via SyncStatus, not a worker
 * failure — we never wedge the schedule on a definitive rejection).
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncEngine: SyncEngine,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val reason = inputData.getString(SYNC_REASON_KEY) ?: "periodic"
        syncEngine.syncNow(reason)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
