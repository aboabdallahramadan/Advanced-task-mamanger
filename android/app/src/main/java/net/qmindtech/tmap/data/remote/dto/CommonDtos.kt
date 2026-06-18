package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class ReorderItem(
    val id: String,
    val rank: String,
)

@Serializable
data class ProblemDetails(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val instance: String? = null,
)
