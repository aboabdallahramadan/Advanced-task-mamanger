package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * Orchestrates ONE sync cycle: push() then pull() (SP3 §4 order). Sets the SyncStatus surface:
 *   - offline (isOnline()==false)            -> Offline, no wire calls, empty SyncResult.
 *   - cycle running                          -> Syncing.
 *   - push network abort (queue intact)      -> Offline (retry next cycle).
 *   - push surfaced rejections/parks         -> Error(summary) after the cycle.
 *   - otherwise                              -> Idle.
 * A mutex makes overlapping syncNow calls serialize (single-flight is otherwise the worker's job).
 */
open class SyncEngine @Inject constructor(
    private val push: PushRunner,
    private val pull: PullRunner,
    private val statusHolder: SyncStatusHolder,
    private val isOnline: () -> Boolean,
) {
    private val mutex = Mutex()

    open suspend fun syncNow(reason: String): SyncResult = mutex.withLock {
        if (!isOnline()) {
            statusHolder.set(SyncStatus.Offline)
            return@withLock SyncResult()
        }
        statusHolder.set(SyncStatus.Syncing)
        try {
            val pushOutcome = push.drain()
            if (pushOutcome.networkAborted) {
                statusHolder.set(SyncStatus.Offline)
                return@withLock SyncResult(
                    pushed = pushOutcome.pushed,
                    rejected = pushOutcome.rejected,
                    parked = pushOutcome.parked,
                )
            }
            val pullOutcome = pull.pullAll()
            val result = SyncResult(
                pushed = pushOutcome.pushed,
                pulled = if (pullOutcome.applied) maxOf(1, pullOutcome.pages) else 0,
                rejected = pushOutcome.rejected,
                parked = pushOutcome.parked,
                fullResynced = pullOutcome.fullResynced,
            )
            // Sticky error: keep Error while ANY op is parked (parkedTotal), even on a later clean
            // cycle with no new rejections/parks — otherwise the only user signal is a one-cycle Error
            // the next cycle silently resets to Idle (BUG 1b). New-this-cycle rejections also surface.
            when {
                pushOutcome.rejected > 0 || pushOutcome.parked > 0 -> {
                    val msg = pushOutcome.rejections.firstOrNull()?.reason ?: "sync rejected an operation"
                    statusHolder.set(SyncStatus.Error(msg))
                }
                pushOutcome.parkedTotal > 0 -> {
                    val n = pushOutcome.parkedTotal
                    statusHolder.set(SyncStatus.Error("$n ${if (n == 1) "change needs" else "changes need"} attention"))
                }
                else -> statusHolder.set(SyncStatus.Idle)
            }
            result
        } catch (e: Exception) {
            statusHolder.set(SyncStatus.Error(e.message ?: "sync failed"))
            SyncResult()
        }
    }
}
