package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

/** Append-only (spec §7.4): only POST exists; no Update/Delete request types. */
@Serializable
data class CreateFocusSessionRequest(
    val id: String? = null,
    val taskId: String? = null,
    val project: String,
    val startedAt: String,
    val endedAt: String,
    val minutes: Int,
    val date: String,
)

@Serializable
data class FocusSessionResponse(
    val id: String,
    val taskId: String?,
    val project: String,
    val startedAt: String,
    val endedAt: String,
    val minutes: Int,
    val date: String,
    val createdAt: String,
    val updatedAt: String,
)
