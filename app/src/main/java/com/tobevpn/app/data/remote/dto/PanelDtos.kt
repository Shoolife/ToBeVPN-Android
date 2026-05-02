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

// --- Subscription info ---

data class PanelSubInfoDto(
    @SerializedName("is_found") val isFound: Boolean,
    val user: PanelSubUserDto?,
    val links: List<String>?,
    @SerializedName("subscription_url") val subscriptionUrl: String?,
)

data class PanelSubUserDto(
    @SerializedName("short_uuid") val shortUuid: String,
    @SerializedName("days_left") val daysLeft: Double,
    @SerializedName("traffic_used") val trafficUsed: String,
    @SerializedName("traffic_limit") val trafficLimit: String,
    @SerializedName("traffic_used_bytes") val trafficUsedBytes: String,
    @SerializedName("traffic_limit_bytes") val trafficLimitBytes: String,
    @SerializedName("lifetime_traffic_used_bytes") val lifetimeTrafficUsedBytes: String,
    val username: String,
    @SerializedName("expires_at") val expiresAt: String?,
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("user_status") val userStatus: String,
    @SerializedName("traffic_limit_strategy") val trafficLimitStrategy: String,
)
