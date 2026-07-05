package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Request/response DTOs for the /api/v1/recurrence endpoints. Field names + types mirror the backend
 * records verbatim (RecurrenceDtos.cs). Enum-valued fields (frequency/endType) are plain Strings
 * carrying the PascalCase wire values ("Daily"/"Weekly", "Never"/"Count"/"Date").
 */

@Serializable
data class RecurringTaskInput(
    val title: String,
    val notes: String,
    val projectId: String?,
    val labels: List<String>,
    val source: String,
    val plannedDate: String?, // yyyy-MM-dd; null => server anchors to today
    val durationMinutes: Int,
    val priority: Int?,
    val reminderMinutes: Int?,
    val id: String? = null,
)

@Serializable
data class RecurrenceRuleInput(
    val frequency: String, // "Daily" | "Weekly"
    val interval: Int,
    val daysOfWeek: List<Int>, // Sunday=0..6; [] for daily
    val endType: String, // "Never" | "Count" | "Date"
    val endCount: Int?,
    val endDate: String?, // yyyy-MM-dd
    val id: String? = null,
)

@Serializable
data class CreateRecurringTaskRequest(
    val task: RecurringTaskInput,
    val rule: RecurrenceRuleInput,
)

/** PATCH /recurrence/rules/{id} — whole-rule replace (id comes from the route). */
@Serializable
data class UpdateRuleRequest(
    val frequency: String,
    val interval: Int,
    val daysOfWeek: List<Int>,
    val endType: String,
    val endCount: Int?,
    val endDate: String?,
)

/** POST /recurrence/rules/{id}/delete-future body. */
@Serializable
data class DeleteSeriesFutureRequest(
    val fromDate: String, // yyyy-MM-dd, required
)

/**
 * INTERNAL outbox envelope for a RECURRENCE + OpType.DELETE op (not a wire body). PushRunner reads
 * `scope` to choose DELETE /rules/{id} (scope="all") vs POST /rules/{id}/delete-future (scope="future").
 */
@Serializable
data class RecurrenceDeletePayload(
    val scope: String, // "all" | "future"
    val fromDate: String? = null, // required when scope == "future"
)
