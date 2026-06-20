package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The sync-status surface (idle / syncing / offline / error) — mirrors the desktop pill. */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data object Offline : SyncStatus
    data class Error(val message: String) : SyncStatus
}

/** App-scoped holder of the current SyncStatus as observable state. */
@Singleton
class SyncStatusHolder @Inject constructor() {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    fun set(s: SyncStatus) { _status.value = s }
}

/** One-cycle summary returned by SyncEngine.syncNow(). */
data class SyncResult(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val rejected: Int = 0,
    val parked: Int = 0,
    val fullResynced: Boolean = false,
)
