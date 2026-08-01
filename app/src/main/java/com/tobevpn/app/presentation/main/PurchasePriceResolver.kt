package com.tobevpn.app.presentation.main

import com.tobevpn.app.data.remote.dto.PurchaseDurationDto
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
internal fun resolvePurchasePrice(
    duration: PurchaseDurationDto,
    preferredCurrencies: List<String>,
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
        return ResolvedPurchasePrice(
            currency = method.currency.trim().uppercase(Locale.ROOT),
            originalAmount = method.originalAmount,
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

    return ResolvedPurchasePrice(
        currency = price.currency.trim().uppercase(Locale.ROOT),
        originalAmount = price.amount,
        finalAmount = price.amount,
        discountPercent = 0,
    )
}
