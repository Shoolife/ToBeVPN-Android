package com.tobevpn.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// Generic API response
data class ApiResponse<T>(
    val success: Boolean,
    val message: String? = null,
    val data: T? = null,
)

// Device bootstrap / refresh — per-device session auth
data class BootstrapRequestDto(
    @SerializedName("device_id") val deviceId: String,
    val platform: String,
    @SerializedName("integrity_token") val integrityToken: String? = null,
    val hwid: String? = null,
    @SerializedName("device_os") val deviceOs: String? = null,
    @SerializedName("ver_os") val osVersion: String? = null,
    @SerializedName("device_model") val deviceModel: String? = null,
    @SerializedName("user_agent") val userAgent: String? = null,
)

data class RefreshRequestDto(
    @SerializedName("refresh_token") val refreshToken: String,
)

data class SessionTokensDto(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("token_type") val tokenType: String = "Bearer",
    @SerializedName("expires_in") val expiresIn: Long,
    @SerializedName("refresh_expires_in") val refreshExpiresIn: Long,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("telegram_id") val telegramId: Long? = null,
    @SerializedName("panel_user_uuid") val panelUserUuid: String? = null,
    @SerializedName("short_uuid") val shortUuid: String? = null,
    @SerializedName("is_linked") val isLinked: Boolean = false,
)

// Deep-link auth — server binds the current device session and returns auth_token.
data class AuthRequestDto(
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("panel_user_uuid") val panelUserUuid: String? = null,
)

data class AuthRequestResponseDto(
    @SerializedName("auth_token") val authToken: String,
)

data class AuthStatusDto(
    val status: String, // "pending" | "completed" | "expired" | "rejected"
    @SerializedName("telegram_id") val telegramId: Long? = null,
    @SerializedName("short_uuid") val shortUuid: String? = null,
    @SerializedName("panel_user_uuid") val panelUserUuid: String? = null,
)

// Device traffic
data class DeviceTrafficDto(
    @SerializedName("anon_traffic_bytes") val anonTrafficBytes: Long,
)

data class CurrentPlanDto(
    @SerializedName("current_plan") val currentPlan: CurrentPlanSnapshotDto? = null,
    @SerializedName("plan_snapshot") val planSnapshot: CurrentPlanSnapshotDto? = null,
    val subscription: CurrentPlanSubscriptionDto? = null,
    @SerializedName("plan_name") val planName: String? = null,
    val name: String? = null,
    val status: String? = null,
    @SerializedName("expire_at") val expireAt: String? = null,
    @SerializedName("expires_at") val expiresAt: String? = null,
)

data class CurrentPlanSnapshotDto(
    val id: Long? = null,
    val name: String? = null,
    val type: String? = null,
    @SerializedName("traffic_limit") val trafficLimit: Long? = null,
    @SerializedName("traffic_limit_bytes") val trafficLimitBytes: Long? = null,
    @SerializedName("device_limit") val deviceLimit: Int? = null,
    val duration: Int? = null,
    @SerializedName("duration_days") val durationDays: Int? = null,
    val tag: String? = null,
    @SerializedName("is_trial") val isTrial: Boolean? = null,
    @SerializedName("traffic_limit_strategy") val trafficLimitStrategy: String? = null,
)

data class CurrentPlanSubscriptionDto(
    @SerializedName("expire_at") val expireAt: String? = null,
    @SerializedName("expires_at") val expiresAt: String? = null,
    @SerializedName("expire_at_ts") val expireAtTs: Long? = null,
    val status: String? = null,
    @SerializedName("stored_status") val storedStatus: String? = null,
    @SerializedName("is_active") val isActive: Boolean? = null,
    @SerializedName("is_expired") val isExpired: Boolean? = null,
    @SerializedName("is_unlimited") val isUnlimited: Boolean? = null,
    @SerializedName("is_trial") val isTrial: Boolean? = null,
    @SerializedName("traffic_limit") val trafficLimit: Long? = null,
    @SerializedName("traffic_limit_bytes") val trafficLimitBytes: Long? = null,
    @SerializedName("traffic_limit_strategy") val trafficLimitStrategy: String? = null,
    @SerializedName("device_limit") val deviceLimit: Int? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("created_at_ts") val createdAtTs: Long? = null,
)

data class DeviceRegisterRequestDto(
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("device_type") val deviceType: String,
    val platform: String,
)

// Unlinks a device belonging to the current account.
data class DeviceUnlinkRequestDto(
    @SerializedName("device_id") val deviceId: String,
)

data class LinkedDeviceDto(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_name") val deviceName: String? = null,
    @SerializedName("device_type") val deviceType: String? = null,
    val platform: String? = null,
    @SerializedName("linked_at") val linkedAt: Long? = null,
    @SerializedName("last_seen_at") val lastSeenAt: Long? = null,
)

data class LinkedDevicesDto(
    @SerializedName("max_devices") val maxDevices: Int,
    val devices: List<LinkedDeviceDto>,
)

// TV pairing — phone confirms with the code shown on TV; identity is taken from session.
data class TvPairCreateRequestDto(
    @SerializedName("device_id") val deviceId: String? = null,
)

data class TvPairCreateResponseDto(
    val code: String,
    @SerializedName("expires_in") val expiresIn: Int,
)

data class TvPairStatusDto(
    val status: String, // "pending" | "completed" | "expired" | "rejected"
    @SerializedName("telegram_id") val telegramId: Long? = null,
    @SerializedName("short_uuid") val shortUuid: String? = null,
    @SerializedName("panel_user_uuid") val panelUserUuid: String? = null,
)

data class TvPairConfirmRequestDto(
    val code: String,
)

// Remote config
data class RemoteConfigDto(
    @SerializedName("free_squad_uuid") val freeSquadUuid: String,
    @SerializedName("free_trial_traffic_bytes") val freeTrialTrafficBytes: Long,
    @SerializedName("free_trial_days") val freeTrialDays: Int,
    // Anonymous (pre-sign-in) allowance. Optional — falls back client-side
    // when the config endpoint doesn't report it.
    @SerializedName("anon_traffic_bytes") val anonTrafficBytes: Long = 0,
)

// Plan
data class PlanDto(
    val type: String, // "FREE_TRIAL" | "PAID" | "EXPIRED"
    @SerializedName("bytes_limit") val bytesLimit: Long = 0,
    @SerializedName("time_limit_seconds") val timeLimitSeconds: Long = 0,
    @SerializedName("bytes_used") val bytesUsed: Long = 0,
    @SerializedName("time_used_seconds") val timeUsedSeconds: Long = 0,
    @SerializedName("expires_at") val expiresAt: Long? = null,
)

// Servers
data class ServerDto(
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val uuid: String,
    val flow: String = "",
    val security: String = "reality",
    val sni: String = "",
    val fingerprint: String = "chrome",
    @SerializedName("public_key") val publicKey: String = "",
    @SerializedName("short_id") val shortId: String = "",
    val network: String = "tcp",
    val path: String = "",
    val mode: String = "",
    val spx: String = "",
    val country: String = "",
)

// Ensure user (anonymous panel user creation/lookup) — server uses session to identify the device.
data class EnsureUserRequestDto(
    val hwid: String? = null,
    @SerializedName("device_os") val deviceOs: String? = null,
    @SerializedName("ver_os") val osVersion: String? = null,
    @SerializedName("device_model") val deviceModel: String? = null,
    @SerializedName("user_agent") val userAgent: String? = null,
)

data class EnsureUserResponseDto(
    @SerializedName("short_uuid") val shortUuid: String,
    @SerializedName("panel_user_uuid") val panelUserUuid: String,
    @SerializedName("traffic_limit_bytes") val trafficLimitBytes: Long,
    @SerializedName("traffic_used_bytes") val trafficUsedBytes: Long,
    @SerializedName("anon_traffic_bytes") val anonTrafficBytes: Long? = null,
    @SerializedName("is_anonymous") val isAnonymous: Boolean = true,
    @SerializedName("telegram_id") val telegramId: Long? = null,
)

// Save email
data class SaveEmailRequestDto(
    @SerializedName("panel_user_uuid") val panelUserUuid: String,
    val email: String,
)
