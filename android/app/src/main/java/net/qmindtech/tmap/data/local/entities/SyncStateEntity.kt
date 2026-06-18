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
)
