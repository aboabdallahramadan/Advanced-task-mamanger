package net.qmindtech.tmap.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "subtasks", indices = [Index("taskId")])
data class SubtaskEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val title: String,
    val completed: Boolean,
    val sortOrder: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val changeSeq: Long,
)
