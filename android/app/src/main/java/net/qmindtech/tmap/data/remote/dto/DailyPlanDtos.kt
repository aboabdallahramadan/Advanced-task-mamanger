package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

/** PUT /daily-plans/{date} upsert body (spec §7.6) — last-writer-wins, full plannedTaskIds replace. */
@Serializable
data class UpsertDailyPlanRequest(
    val committedAt: String? = null,
    val plannedTaskIds: List<String>,
    val plannedMinutes: Int,
)

@Serializable
data class DailyPlanResponse(
    val date: String,
    val committedAt: String,
    val plannedTaskIds: List<String> = emptyList(),
    val plannedMinutes: Int,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)
