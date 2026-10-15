package com.tobevpn.app.presentation.main

import com.tobevpn.app.data.remote.dto.PurchaseDurationDto
import com.tobevpn.app.data.remote.dto.PurchasePaymentMethodDto
import com.tobevpn.app.data.remote.dto.PurchasePriceDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchasePriceResolverTest {

    @Test
    fun `uses server final amount and discount for preferred currency`() {
        val result = resolvePurchasePrice(
            duration = duration(
                prices = listOf(PurchasePriceDto(currency = "RUB", amount = "1000")),
                methods = listOf(
                    method(currency = "USD", original = "12.00", final = "10.80", discount = 10),
                    method(currency = "RUB", original = "1000", final = "900", discount = 10),
                ),
            ),
            preferredCurrencies = listOf("RUB", "USD"),
        )

        requireNotNull(result)
        assertEquals("RUB", result.currency)
        assertEquals("1000", result.originalAmount)
        assertEquals("900", result.finalAmount)
        assertEquals(10, result.discountPercent)
        assertTrue(result.hasDiscount)
    }

    @Test
    fun `currency order is respected independently of method order`() {
        val result = resolvePurchasePrice(
            duration = duration(
                methods = listOf(
                    method(currency = "RUB", original = "1000", final = "800", discount = 20),
                    method(currency = "USD", original = "12.00", final = "9.60", discount = 20),
                ),
            ),
            preferredCurrencies = listOf("USD", "RUB"),
        )

        requireNotNull(result)
        assertEquals("USD", result.currency)
        assertEquals("9.60", result.finalAmount)
    }

    @Test
    fun `legacy price is used only when payment methods are absent`() {
        val result = resolvePurchasePrice(
            duration = duration(
                prices = listOf(
                    PurchasePriceDto(currency = "USD", amount = "12.00"),
                    PurchasePriceDto(currency = "RUB", amount = "1000"),
                ),
            ),
            preferredCurrencies = listOf("RUB", "USD"),
        )

        requireNotNull(result)
        assertEquals("RUB", result.currency)
        assertEquals("1000", result.finalAmount)
        assertEquals(0, result.discountPercent)
        assertFalse(result.hasDiscount)
    }

    @Test
    fun `rounded equal amounts are not presented as a discount`() {
        val result = resolvePurchasePrice(
            duration = duration(
                methods = listOf(
                    method(currency = "XTR", original = "1", final = "1", discount = 10),
                ),
            ),
            preferredCurrencies = listOf("XTR"),
        )

        requireNotNull(result)
        assertFalse(result.hasDiscount)
    }

    @Test
    fun `invalid server discount is safely clamped`() {
        val result = resolvePurchasePrice(
            duration = duration(
                methods = listOf(
                    method(currency = "RUB", original = "1000", final = "1", discount = 150),
                ),
            ),
            preferredCurrencies = listOf("RUB"),
        )

        requireNotNull(result)
        assertEquals(100, result.discountPercent)
    }

    private fun duration(
        prices: List<PurchasePriceDto> = emptyList(),
        methods: List<PurchasePaymentMethodDto> = emptyList(),
    ) = PurchaseDurationDto(
        id = 1,
        days = 30,
        prices = prices,
        paymentMethods = methods,
    )

    private fun method(
        currency: String,
        original: String,
        final: String,
        discount: Int,
    ) = PurchasePaymentMethodDto(
        gatewayType = "TEST",
        currency = currency,
        originalAmount = original,
        finalAmount = final,
        discountPercent = discount,
    )

    @Test
    fun termSavingStrikesThroughMonthlyPriceTimesMonths() {
        val month = term(30, "450.00", "450.00", "450", 0)
        val quarter = term(90, "1199.00", "1199.00", "1199", 11)
        val resolved = requireNotNull(
            resolvePurchasePrice(quarter, listOf("RUB"), listOf(month, quarter)),
        )
        assertEquals("1350", resolved.originalAmount)
        assertEquals("1199", resolved.finalAmount)
        assertEquals(11, resolved.discountPercent)
        assertTrue(resolved.hasDiscount)
    }

    @Test
    fun promoCodeSavingUsesTheSameBase() {
        val month = term(30, "450.00", "450.00", "360", 20)
        val quarter = term(90, "1199.00", "1199.00", "959", 28)
        val resolved = requireNotNull(
            resolvePurchasePrice(quarter, listOf("RUB"), listOf(month, quarter)),
        )
        assertEquals("1350", resolved.originalAmount)
        assertEquals("959", resolved.finalAmount)
        assertEquals(28, resolved.discountPercent)
        // The shortest term itself strikes through its own price.
        val monthResolved = requireNotNull(
            resolvePurchasePrice(month, listOf("RUB"), listOf(month, quarter)),
        )
        assertEquals("450.00", monthResolved.originalAmount)
        assertEquals(20, monthResolved.discountPercent)
    }

    @Test
    fun dailyShortestTermScalesByDays() {
        val day = term(1, "15.00", "15.00", "15", 0)
        val quarter = term(90, "550.00", "550.00", "550", 59)
        val year = term(365, "1500.00", "1500.00", "1500", 72)
        val all = listOf(day, quarter, year)
        assertEquals(
            "1350",
            requireNotNull(resolvePurchasePrice(quarter, listOf("RUB"), all)).originalAmount,
        )
        assertEquals(
            "5475",
            requireNotNull(resolvePurchasePrice(year, listOf("RUB"), all)).originalAmount,
        )
    }

    private fun term(
        days: Int,
        price: String,
        original: String,
        final: String,
        discount: Int,
    ) = PurchaseDurationDto(
        id = days.toLong(),
        days = days,
        prices = listOf(PurchasePriceDto(currency = "RUB", amount = price, discountPercent = discount)),
        paymentMethods = listOf(
            PurchasePaymentMethodDto(
                gatewayType = "PLATEGA",
                currency = "RUB",
                originalAmount = original,
                finalAmount = final,
                discountPercent = discount,
            ),
        ),
    )
}
