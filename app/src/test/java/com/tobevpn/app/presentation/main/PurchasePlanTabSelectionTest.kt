package com.tobevpn.app.presentation.main

import com.tobevpn.app.data.remote.dto.PurchasePlanDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PurchasePlanTabSelectionTest {
    @Test
    fun `regular subscription card always selects first tariff`() {
        val plans = listOf(
            plan(id = 1L, name = "Комфорт", purchaseType = "CHANGE"),
            plan(id = 2L, name = "Корпорат", purchaseType = "RENEW"),
        )

        assertEquals(
            "1",
            initialPurchasePlanTabKey(
                plans = plans,
                currentPlanDisplayName = "Корпорат",
                selectCurrentPlan = false,
            ),
        )
    }

    @Test
    fun `renewal reminder selects current tariff`() {
        val plans = listOf(
            plan(id = 1L, name = "Комфорт", purchaseType = "CHANGE"),
            plan(id = 2L, name = "Корпорат", purchaseType = "RENEW"),
        )

        assertEquals(
            "2",
            initialPurchasePlanTabKey(
                plans = plans,
                currentPlanDisplayName = "Корпорат",
                selectCurrentPlan = true,
            ),
        )
    }

    @Test
    fun `server renew marker selects current tariff`() {
        val plans = listOf(
            plan(id = 1L, name = "Комфорт", purchaseType = "CHANGE"),
            plan(id = 2L, name = "Корпорат", purchaseType = "renew"),
        )

        assertEquals("2", preferredRenewalTariffKey(plans, "Другое название"))
    }

    @Test
    fun `display name is fallback for older response`() {
        val plans = listOf(
            plan(id = 1L, name = "Комфорт", purchaseType = "CHANGE"),
            plan(id = 2L, name = "  КОРПОРАТ   ", purchaseType = "CHANGE"),
        )

        assertEquals("2", preferredRenewalTariffKey(plans, "корпорат"))
    }

    @Test
    fun `unknown current plan leaves fallback to caller`() {
        val plans = listOf(plan(id = 1L, name = "Комфорт", purchaseType = "CHANGE"))

        assertNull(preferredRenewalTariffKey(plans, "Корпорат"))
    }

    private fun plan(
        id: Long,
        name: String,
        purchaseType: String,
    ): PurchasePlanDto = PurchasePlanDto(
        id = id,
        publicCode = "plan-$id",
        name = name,
        type = "BOTH",
        availability = "ALL",
        purchaseType = purchaseType,
    )
}
