package net.qmindtech.tmap.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

/**
 * A recurrence rule synced from the server (created on any client, incl. this one). Instances are
 * materialized server-side; this row is the definition. `frequency`/`endType` are stored as the
 * PascalCase wire strings ("Daily"/"Weekly", "Never"/"Count"/"Date"). `daysOfWeek` is Sunday=0..6,
 * empty for daily.
 */
@Entity(tableName = "recurrence_rules")
data class RecurrenceRuleEntity(
    @PrimaryKey val id: String,
    val frequency: String,
    val interval: Int,
    val daysOfWeek: List<Int>,
    val endType: String,
    val endCount: Int?,
    val endDate: LocalDate?,
    val generatedUntil: LocalDate?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val changeSeq: Long,
    val deletedAt: Instant? = null,
)
