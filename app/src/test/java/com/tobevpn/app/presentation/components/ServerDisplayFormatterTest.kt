package com.tobevpn.app.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerDisplayFormatterTest {

    @Test
    fun `RU prefix supplies Russian flag when panel country is missing`() {
        assertEquals("RU", serverCountryCodeForUi("", "RU -> WORLD 4 [БС] [x2]"))
        assertEquals("🇷🇺", countryFlagForUi("", "RU -> WORLD 4 [БС] [x2]"))
    }

    @Test
    fun `Russian country names supply flag without panel metadata`() {
        assertEquals("RU", serverCountryCodeForUi("", "Россия без рекламы"))
        assertEquals("RU", serverCountryCodeForUi("", "Russia cascade"))
    }

    @Test
    fun `RU must be a complete token`() {
        assertEquals("", serverCountryCodeForUi("", "Brunei route"))
    }
}
