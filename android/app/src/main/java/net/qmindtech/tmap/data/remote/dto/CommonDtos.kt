package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class ReorderItem(
    val id: String,
    val rank: String,
)
