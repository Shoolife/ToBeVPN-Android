package com.tobevpn.app.data.remote.dto

import com.google.gson.annotations.SerializedName

/** Request body for POST /api/user/promocodes/activate. */
data class PromocodeActivateRequestDto(
    val code: String,
    @SerializedName("request_id") val requestId: String,
)

/** Reward returned immediately after a promocode is applied. */
data class PromocodeActivationResultDto(
    @SerializedName("request_id") val requestId: String? = null,
    val code: String? = null,
    @SerializedName("reward_type") val rewardType: String? = null,
    val reward: Int? = null,
    @SerializedName("plan_snapshot") val planSnapshot: PromocodePlanSnapshotDto? = null,
)

/** A deliberately small public snapshot for SUBSCRIPTION rewards. */
data class PromocodePlanSnapshotDto(
    val name: String? = null,
    val duration: Int? = null,
)

/** Response payload of GET /api/user/promocodes. */
data class PromocodeHistoryDto(
    @SerializedName("telegram_id") val telegramId: Long = 0,
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
    val promocodes: List<PromocodeHistoryItemDto>? = null,
)

data class PromocodeHistoryItemDto(
    @SerializedName("activation_id") val activationId: Long? = null,
    @SerializedName("promocode_id") val promocodeId: Long? = null,
    val code: String? = null,
    @SerializedName("reward_type") val rewardType: String? = null,
    val reward: Int? = null,
    @SerializedName("plan_snapshot") val planSnapshot: PromocodePlanSnapshotDto? = null,
    @SerializedName("activated_at") val activatedAt: String? = null,
)

/** FastAPI wraps structured promocode errors inside the top-level `detail` key. */
data class PromocodeErrorEnvelopeDto(
    val detail: PromocodeErrorDetailDto? = null,
)

data class PromocodeErrorDetailDto(
    val code: String? = null,
    val message: String? = null,
)
