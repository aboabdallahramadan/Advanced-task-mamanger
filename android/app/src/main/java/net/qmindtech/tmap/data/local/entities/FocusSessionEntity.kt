package net.qmindtech.tmap.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

/** An append-only focus session (spec §7.4). `project` is a NAME snapshot, not an FK. */
@Entity(tableName = "focus_sessions", indices = [Index("taskId"), Index("date")])
data class FocusSessionEntity(
    @PrimaryKey val id: String,
    val taskId: String?,
    val project: String,
    val startedAt: Instant,
    val endedAt: Instant,
    val minutes: Int,
    val date: LocalDate,
    val createdAt: Instant,
    val updatedAt: Instant,
    val changeSeq: Long,
    val deletedAt: Instant? = null,
)
