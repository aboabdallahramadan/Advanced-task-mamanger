package net.qmindtech.tmap.data.sync

/**
 * Schedules the background sync work. Repositories call requestExpeditedSync() after each
 * write (debounced 2 s); the app calls schedulePeriodic() once at startup (15-min safety net).
 * The WorkManager-backed implementation is WorkManagerSyncScheduler (P4.6); a fake implements
 * this in repository unit tests so they assert "a write nudged sync" without WorkManager.
 */
interface SyncScheduler {
    /** Enqueue an expedited one-shot sync, debounced via a unique REPLACE policy (2 s initial delay). */
    fun requestExpeditedSync()

    /** Enqueue the 15-min periodic sync (unique KEEP, NetworkType.CONNECTED). Idempotent. */
    fun schedulePeriodic()

    /** Cancel all sync work (used on definitive logout / teardown). */
    fun cancelAll()
}
