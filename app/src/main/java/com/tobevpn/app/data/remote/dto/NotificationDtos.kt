package com.tobevpn.app.data.remote.dto

import com.google.gson.annotations.SerializedName

/** One announcement in the feed (`GET /api/device/notifications`). */
data class NotificationItemDto(
    val id: String,
    val title: String? = null,
    val preview: String? = null,
    @SerializedName("published_at") val publishedAt: String? = null,
    @SerializedName("read_at") val readAt: String? = null,
)

/** One announcement with its full text (`GET /api/device/notifications/{id}`). */
data class NotificationDetailDto(
    val id: String,
    val title: String? = null,
    val preview: String? = null,
    @SerializedName("published_at") val publishedAt: String? = null,
    @SerializedName("read_at") val readAt: String? = null,
    val body: String? = null,
    val action: String? = null,
)

data class NotificationReadDto(
    @SerializedName("read_at") val readAt: String? = null,
)

data class PushRegistrationRequestDto(
    val token: String,
)
