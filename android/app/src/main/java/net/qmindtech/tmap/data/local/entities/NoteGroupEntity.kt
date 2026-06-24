package net.qmindtech.tmap.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "note_groups", indices = [Index("projectId")])
data class NoteGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String,
    val projectId: String?,
    val rank: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val changeSeq: Long,
    val deletedAt: Instant? = null,
)
