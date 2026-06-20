package net.qmindtech.tmap.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

const val EXPEDITED_SYNC_WORK = "expedited_sync"
const val PERIODIC_SYNC_WORK = "periodic_sync"
const val EXPEDITED_DEBOUNCE_SECONDS = 2L
const val PERIODIC_MINUTES = 15L

/**
 * WorkManager-backed SyncScheduler.
 *  - requestExpeditedSync(): a unique-REPLACE one-shot with a 2 s initial delay — the post-write
 *    debounce (a burst of writes collapses to a single run). The short delay keeps it prompt;
 *    CONNECTED-constrained so it waits for a network. (WorkManager forbids combining an initial
 *    delay with setExpedited() — "Expedited jobs cannot be delayed" — so the debounced delay wins;
 *    that fallback to a normal request is exactly the debounced behavior we want.)
 *  - schedulePeriodic(): a unique-KEEP 15-min periodic worker (WorkManager floor), CONNECTED — the
 *    safety net. Idempotent.
 *  - cancelAll(): cancels both unique works (definitive logout / teardown).
 * Connectivity-regain is handled by the CONNECTED constraint on both works (WorkManager re-runs them
 * when the network returns); the app additionally calls requestExpeditedSync() on a NetworkCallback
 * for immediacy (wired in AppModule / the app shell).
 */
class WorkManagerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncScheduler {

    private val workManager get() = WorkManager.getInstance(context)

    private val connectedConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    override fun requestExpeditedSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(EXPEDITED_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .setConstraints(connectedConstraints)
            .setInputData(Data.Builder().putString(SYNC_REASON_KEY, "write").build())
            .build()
        workManager.enqueueUniqueWork(EXPEDITED_SYNC_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_MINUTES, TimeUnit.MINUTES)
            .setConstraints(connectedConstraints)
            .setInputData(Data.Builder().putString(SYNC_REASON_KEY, "periodic").build())
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_SYNC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancelAll() {
        workManager.cancelUniqueWork(EXPEDITED_SYNC_WORK)
        workManager.cancelUniqueWork(PERIODIC_SYNC_WORK)
    }
}
