package com.tobevpn.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// Generic panel response wrapper
data class PanelResponse<T>(
    val response: T,
)

// --- Nodes ---

data class PanelNodeDto(
    val uuid: String,
    val name: String,
    val address: String,
    val port: Int,
    @SerializedName("is_connected") val isConnected: Boolean,
    @SerializedName("is_disabled") val isDisabled: Boolean,
    @SerializedName("country_code") val countryCode: String,
    @SerializedName("view_position") val viewPosition: Int,
)

// --- Users ---

data class PanelUserDto(
    val uuid: String,
    @SerializedName("short_uuid") val shortUuid: String,
    val username: String,
    val status: String,
    @SerializedName("traffic_limit_bytes") val trafficLimitBytes: Long,
    @SerializedName("traffic_limit_strategy") val trafficLimitStrategy: String,
    @SerializedName("expire_at") val expireAt: String?,
    @SerializedName("telegram_id") val telegramId: Long?,
    @SerializedName("vless_uuid") val vlessUuid: String,
    @SerializedName("subscription_url") val subscriptionUrl: String,
    @SerializedName("active_internal_squads") val activeInternalSquads: List<PanelSquadRefDto>,
    @SerializedName("user_traffic") val userTraffic: PanelUserTrafficDto?,
    @SerializedName("hwid_device_limit") val hwidDeviceLimit: Int? = null,
    val email: String? = null,
    // Free-text panel description; the bot stores the Telegram profile here as
    // "name: <full name>\nusername: <@handle>".
    val description: String? = null,
)

data class PanelSquadRefDto(
    val uuid: String,
    val name: String,
)

data class PanelUserTrafficDto(
    @SerializedName("used_traffic_bytes") val usedTrafficBytes: Long,
    @SerializedName("lifetime_used_traffic_bytes") val lifetimeUsedTrafficBytes: Long,
    @SerializedName("online_at") val onlineAt: String?,
    @SerializedName("last_connected_node_uuid") val lastConnectedNodeUuid: String?,
)
