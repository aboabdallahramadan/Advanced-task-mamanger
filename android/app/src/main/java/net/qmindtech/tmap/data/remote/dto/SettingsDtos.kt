package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class SaveSettingsRequest(
    val settings: Map<String, String>,
    val timeZoneId: String? = null,
)

@Serializable
data class SettingsResponse(
    val settings: Map<String, String>,
    val timeZoneId: String,
)
