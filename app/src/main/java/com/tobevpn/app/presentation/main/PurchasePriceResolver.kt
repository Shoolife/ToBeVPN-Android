package com.tobevpn.app.presentation.main

import com.tobevpn.app.data.remote.dto.PurchaseDurationDto
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * A price calculated by the server for one of the currently available
 * payment methods.
 *
 * The client deliberately does not calculate the discount itself. Remnashop
 * owns the rounding rules for every currency, so [finalAmount] is the only
 * amount that should be shown as the price the user will actually pay.
 */
internal data class ResolvedPurchasePrice(
    val currency: String,
    val originalAmount: String,
    val finalAmount: String,
    val discountPercent: Int,
) {
    val hasDiscount: Boolean
        get() {
            if (discountPercent <= 0) return false
            val original = originalAmount.toBigDecimalOrNull() ?: return false
            val final = finalAmount.toBigDecimalOrNull() ?: return false
            return final < original
        }
}

/**
 * Prefers server-calculated payment method prices and falls back to the
 * legacy base price list only when the server does not return any methods.
 */
/**
 * Prefers server-calculated payment method prices and falls back to the
 * legacy base price list only when the server does not return any methods.
 *
 * The server states a term's saving (discount_percent) against the plan's
 * shortest term scaled to this term's days: with a 15 ₽ day, 90 days are
 * 1350 ₽ and 550 ₽ for them is 59% off; with a month as the shortest term,
 * 90 days are 3 x 450 = 1350 ₽. That scaled price is what [originalAmount]
 * carries, so the struck-through price matches the percentage. The shortest
 * term itself keeps its own original amount (only a promo code shows there).
 */
internal fun resolvePurchasePrice(
    duration: PurchaseDurationDto,
    preferredCurrencies: List<String>,
    planDurations: List<PurchaseDurationDto> = listOf(duration),
): ResolvedPurchasePrice? {
    val currencyOrder = preferredCurrencies
        .map { it.trim().uppercase(Locale.ROOT) }
        .filter { it.isNotEmpty() }
        .distinct()

    val methods = duration.paymentMethods.orEmpty().filter {
        it.currency.isNotBlank() &&
            it.originalAmount.isNotBlank() &&
            it.finalAmount.isNotBlank()
    }
    val method = currencyOrder.firstNotNullOfOrNull { preferredCurrency ->
        methods.firstOrNull { it.currency.equals(preferredCurrency, ignoreCase = true) }
    } ?: methods.firstOrNull()

    if (method != null) {
        val currency = method.currency.trim().uppercase(Locale.ROOT)
        return ResolvedPurchasePrice(
            currency = currency,
            originalAmount = scaledShortestTermAmount(duration, planDurations, currency)
                ?: method.originalAmount,
            finalAmount = method.finalAmount,
            discountPercent = method.discountPercent.coerceIn(0, 100),
        )
    }

    val prices = duration.prices.orEmpty().filter {
        it.currency.isNotBlank() && it.amount.isNotBlank()
    }
    val price = currencyOrder.firstNotNullOfOrNull { preferredCurrency ->
        prices.firstOrNull { it.currency.equals(preferredCurrency, ignoreCase = true) }
    } ?: prices.firstOrNull() ?: return null

    val currency = price.currency.trim().uppercase(Locale.ROOT)
    return ResolvedPurchasePrice(
        currency = currency,
        originalAmount = scaledShortestTermAmount(duration, planDurations, currency)
            ?: price.amount,
        finalAmount = price.amount,
        discountPercent = price.discountPercent.coerceIn(0, 100),
    )
}

/**
 * The plan's shortest term price in [currency] scaled to [duration]'s days,
 * or null for the shortest term itself or without such a price.
 */
private fun scaledShortestTermAmount(
    duration: PurchaseDurationDto,
    planDurations: List<PurchaseDurationDto>,
    currency: String,
): String? {
    val shortest = planDurations
        .filter { it.days > 0 }
        .mapNotNull { term ->
            val amount = term.prices.orEmpty()
                .firstOrNull { it.currency.equals(currency, ignoreCase = true) }
                ?.amount
                ?: term.paymentMethods.orEmpty()
                    .firstOrNull { it.currency.equals(currency, ignoreCase = true) }
                    ?.originalAmount
            amount?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.let { term.days to it }
        }
        .minByOrNull { it.first }
        ?: return null
    if (duration.days <= shortest.first) return null
    return shortest.second
        .multiply(BigDecimal(duration.days))
        .divide(BigDecimal(shortest.first), 2, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
}
