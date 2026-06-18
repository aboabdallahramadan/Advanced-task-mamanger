package net.qmindtech.tmap.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String,
    val emoji: String,
    val rank: String?,
    val actualTimeMinutes: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val changeSeq: Long,
)
