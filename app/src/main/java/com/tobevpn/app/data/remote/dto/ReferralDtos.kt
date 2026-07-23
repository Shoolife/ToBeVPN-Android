package com.tobevpn.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ReferralUserDto(
    @SerializedName("telegram_id") val telegramId: Long? = null,
    @SerializedName("display_name") val displayName: String? = null,
)

data class ReferralListItemDto(
    @SerializedName("telegram_id") val telegramId: Long? = null,
    @SerializedName("display_name") val displayName: String? = null,
    val level: Int = 1,
    @SerializedName("created_at") val createdAt: String? = null,
)

data class SetReferrerRequestDto(
    @SerializedName("referrer_id") val referrerId: Long,
)

data class SetReferrerResponseDto(
    @SerializedName("referrer_id") val referrerId: Long? = null,
    val created: Boolean = false,
)

data class ReferralsDto(
    @SerializedName("referral_code") val referralCode: String? = null,
    @SerializedName("referral_url") val referralUrl: String? = null,
    val referrer: ReferralUserDto? = null,
    val total: Int = 0,
    // RemnaShop v0.9.4 shipped this field as "referals". Keep that spelling
    // as an alternate so the app also accepts the corrected server contract.
    @SerializedName(value = "referrals", alternate = ["referals"])
    val referrals: List<ReferralListItemDto>? = null,
    val limit: Int = 20,
    val offset: Int = 0,
)
