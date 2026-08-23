package com.tobevpn.app.presentation.main

import com.tobevpn.app.data.remote.dto.PurchasePlanDto
import java.util.Locale

/**
 * Returns the tab to show when opening the subscription sheet.
 * A regular subscription-card click always starts with the first tab, while
 * the renewal reminder opens the user's current plan.
 */
internal fun initialPurchasePlanTabKey(
    plans: List<PurchasePlanDto>,
    currentPlanDisplayName: String?,
    selectCurrentPlan: Boolean,
): String? {
    val firstPlanKey = plans.firstOrNull()?.id?.toString() ?: return null
    if (!selectCurrentPlan) return firstPlanKey
    return preferredRenewalTariffKey(plans, currentPlanDisplayName) ?: firstPlanKey
}

/**
 * Resolves the tariff tab that represents the user's current subscription.
 * `purchase_type=RENEW` is the server's authoritative marker. Name matching is
 * retained for older/cached payloads that did not expose the marker reliably.
 */
internal fun preferredRenewalTariffKey(
    plans: List<PurchasePlanDto>,
    currentPlanDisplayName: String?,
): String? {
    plans.firstOrNull { plan ->
        plan.purchaseType.trim().equals("RENEW", ignoreCase = true)
    }?.let { return it.id.toString() }

    val normalizedCurrentName = currentPlanDisplayName.normalizedPlanName()
        .takeIf { it.isNotEmpty() }
        ?: return null
    return plans.firstOrNull { it.name.normalizedPlanName() == normalizedCurrentName }
        ?.id
        ?.toString()
}

private fun String?.normalizedPlanName(): String =
    this
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.replace(PLAN_WHITESPACE, " ")
        .orEmpty()

private val PLAN_WHITESPACE = Regex("\\s+")
