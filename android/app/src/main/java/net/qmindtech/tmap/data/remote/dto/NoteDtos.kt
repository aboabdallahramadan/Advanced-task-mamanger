package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateNoteRequest(
    val id: String? = null,
    val groupId: String? = null,
    val projectId: String? = null,
    val title: String,
    val content: String,
    val rank: String? = null,
)

@Serializable
data class UpdateNoteRequest(
    val groupId: String? = null,
    val projectId: String? = null,
    val title: String? = null,
    val content: String? = null,
    val rank: String? = null,
)

@Serializable
data class NoteResponse(
    val id: String,
    val groupId: String?,
    val projectId: String?,
    val title: String,
    val content: String,
    val rank: String?,
    val createdAt: String,
    val updatedAt: String,
)
