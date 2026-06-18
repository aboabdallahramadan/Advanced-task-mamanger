package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateSubtaskRequest(
    val id: String? = null,
    val title: String,
)

@Serializable
data class UpdateSubtaskRequest(
    val title: String? = null,
    val completed: Boolean? = null,
    val sortOrder: Int? = null,
)

@Serializable
data class SubtaskResponse(
    val id: String,
    val taskId: String,
    val title: String,
    val completed: Boolean,
    val sortOrder: Int,
    val createdAt: String,
    val updatedAt: String,
)
