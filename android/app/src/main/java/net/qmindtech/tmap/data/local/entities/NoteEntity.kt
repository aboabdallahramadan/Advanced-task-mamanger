package net.qmindtech.tmap.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * A synced note. All columns mirror the backend NoteResponse EXCEPT [pinnedAt], which is a
 * LOCAL-ONLY affordance (spec §7.7): it is never sent to the server, never enqueued to the outbox,
 * and is acceptably lost on a destructive resync (pins are cosmetic).
 */
@Entity(tableName = "notes", indices = [Index("groupId"), Index("projectId")])
data class NoteEntity(
    @PrimaryKey val id: String,
    val groupId: String?,
    val projectId: String?,
    val title: String,
    val content: String,
    val rank: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val changeSeq: Long,
    val deletedAt: Instant? = null,
    val pinnedAt: Instant? = null,
)
