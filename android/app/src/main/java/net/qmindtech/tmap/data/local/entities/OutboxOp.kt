package net.qmindtech.tmap.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import java.time.Instant

@Entity(tableName = "outbox")
data class OutboxOp(
    @PrimaryKey(autoGenerate = true) val localSeq: Long = 0,
    val entityType: EntityType,
    val entityId: String,
    val opType: OpType,
    val payloadJson: String,
    val attempts: Int = 0,
    val parkedAt: Instant? = null,
    val createdAt: Instant,
)
