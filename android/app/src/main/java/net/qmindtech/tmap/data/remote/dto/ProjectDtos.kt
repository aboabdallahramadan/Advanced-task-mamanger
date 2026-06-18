package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateProjectRequest(
    val id: String? = null,
    val name: String,
    val color: String,
    val emoji: String,
    val rank: String? = null,
)

@Serializable
data class UpdateProjectRequest(
    val name: String? = null,
    val color: String? = null,
    val emoji: String? = null,
    val rank: String? = null,
)

@Serializable
data class ProjectResponse(
    val id: String,
    val name: String,
    val color: String,
    val emoji: String,
    val rank: String,
    val actualTimeMinutes: Int,
    val createdAt: String,
    val updatedAt: String,
)
