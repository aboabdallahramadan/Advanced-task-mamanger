package net.qmindtech.tmap.data.recurrence

import java.time.LocalDate

/** Enum name IS the PascalCase wire value ("Daily"/"Weekly"). */
enum class RecurrenceFrequency { Daily, Weekly }

/** Enum name IS the PascalCase wire value ("Never"/"Count"/"Date"). */
enum class RecurrenceEndType { Never, Count, Date }

/** UI/repository-facing recurrence shape (before normalization to the wire DTO). */
data class RecurrenceDraft(
    val frequency: RecurrenceFrequency,
    val interval: Int,
    val daysOfWeek: List<Int>,
    val endType: RecurrenceEndType,
    val endCount: Int?,
    val endDate: LocalDate?,
)
