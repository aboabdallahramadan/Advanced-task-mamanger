package net.qmindtech.tmap.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

/**
 * The committed plan for a single day (spec §7.6). The natural key is [date] (DateOnly on the
 * backend) — there is no Guid id. `plannedTaskIds` is an ordered list persisted via the existing
 * Converters.fromStringList. Upsert is last-writer-wins (full plannedTaskIds replacement).
 */
@Entity(tableName = "daily_plans")
data class DailyPlanEntity(
    @PrimaryKey val date: LocalDate,
    val committedAt: Instant,
    val plannedTaskIds: List<String>,
    val plannedMinutes: Int,
    val changeSeq: Long,
    val deletedAt: Instant? = null,
)
