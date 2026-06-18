package net.qmindtech.tmap.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import net.qmindtech.tmap.data.local.TaskStatus
import java.time.Instant
import java.time.LocalDate

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val notes: String?,
    val projectId: String?,
    val labels: List<String>,
    val source: String?,
    val status: TaskStatus,
    val plannedDate: LocalDate?,
    val scheduledStart: Instant?,
    val scheduledEnd: Instant?,
    val durationMinutes: Int?,
    val actualTimeMinutes: Int = 0,
    val priority: Int?,
    val reminderMinutes: Int?,
    val rank: String?,
    val dueDate: LocalDate?,
    val recurrenceRuleId: String?,
    val isRecurrenceTemplate: Boolean = false,
    val recurrenceDetached: Boolean = false,
    val recurrenceOriginalDate: LocalDate?,
    val completedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val changeSeq: Long,
)
