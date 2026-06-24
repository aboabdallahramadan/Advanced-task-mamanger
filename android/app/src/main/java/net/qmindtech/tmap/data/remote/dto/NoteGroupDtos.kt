package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateNoteGroupRequest(
    val id: String? = null,
    val name: String,
    val emoji: String,
    val projectId: String? = null,
    val rank: String? = null,
)

@Serializable
data class UpdateNoteGroupRequest(
    val name: String? = null,
    val emoji: String? = null,
    val projectId: String? = null,
    val rank: String? = null,
)

@Serializable
data class NoteGroupResponse(
    val id: String,
    val name: String,
    val emoji: String,
    val projectId: String?,
    val rank: String?,
    val createdAt: String,
    val updatedAt: String,
)
