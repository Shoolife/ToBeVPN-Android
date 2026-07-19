package com.tobevpn.app.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * Response payload of GET /api/purchase/plans.
 *
 * List fields are nullable: Gson bypasses Kotlin constructors, so a key the
 * server omits lands as null even on a non-null type with a default value.
 * Call sites use orEmpty().
 */
data class PurchasePlansDto(
    @SerializedName("telegram_id") val telegramId: Long,
    @SerializedName("effective_discount_percent") val effectiveDiscountPercent: Int = 0,
    val plans: List<PurchasePlanDto>? = null,
)

data class PurchasePlanDto(
    val id: Long,
    @SerializedName("public_code") val publicCode: String,
    val name: String,
    val description: String? = null,
    // "UNLIMITED" | "BOTH" | ... (server-defined)
    val type: String,
    // "ALL" | "ALLOWED" | ...
    val availability: String,
    // "NEW" | "RENEW" | "CHANGE"
    @SerializedName("purchase_type") val purchaseType: String,
    @SerializedName("traffic_limit") val trafficLimit: Long = 0,
    // "NO_RESET" | "DAY" | "MONTH_ROLLING" | "MONTH" | ...
    @SerializedName("traffic_limit_strategy") val trafficLimitStrategy: String? = null,
    @SerializedName("device_limit") val deviceLimit: Int = 0,
    val tag: String? = null,
    @SerializedName("order_index") val orderIndex: Int = 0,
    @SerializedName("internal_squad_uuids") val internalSquadUuids: List<String>? = null,
    @SerializedName("external_squad_uuid") val externalSquadUuid: String? = null,
    val durations: List<PurchaseDurationDto>? = null,
)

data class PurchaseDurationDto(
    val id: Long,
    val days: Int,
    @SerializedName("order_index") val orderIndex: Int = 0,
    @SerializedName("bot_start_param") val botStartParam: String? = null,
    @SerializedName("bot_payment_url") val botPaymentUrl: String? = null,
    val prices: List<PurchasePriceDto>? = null,
    @SerializedName("payment_methods") val paymentMethods: List<PurchasePaymentMethodDto>? = null,
)

data class PurchasePriceDto(
    // "USD" | "RUB" | "XTR" (server-defined in-app currency)
    val currency: String,
    // Decimal string, e.g. "15.00"
    val amount: String,
)

data class PurchasePaymentMethodDto(
    // Server-defined gateway type.
    @SerializedName("gateway_type") val gatewayType: String,
    val currency: String,
    @SerializedName("original_amount") val originalAmount: String,
    @SerializedName("final_amount") val finalAmount: String,
    @SerializedName("discount_percent") val discountPercent: Int = 0,
)
