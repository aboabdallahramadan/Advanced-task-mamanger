package net.qmindtech.tmap.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val lastSeq: Long = 0,
    val initialSyncComplete: Boolean = false,
    val localSchemaVersion: Int = 1,
    val lastSyncAt: Instant? = null,
    val lastError: String? = null,
    /**
     * Durable "a definitive-4xx drop happened, local state may have diverged from server truth"
     * flag (SP3 recoverGhostRows/scheduleRecovery mirror). PushRunner sets it on any Drop; PullRunner
     * runs ONE bounded from-0 recovery pull at the end of a clean pull cycle, then clears it.
     */
    val pendingRecovery: Boolean = false,
)
