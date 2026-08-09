package com.tobevpn.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnRepositoryCountryTest {

    @Test
    fun `empty metadata keeps previously known country`() {
        assertEquals(
            "DE",
            resolveRefreshedServerCountry(
                serverName = "Private node",
                freshCountry = "",
                fallbackCountry = "DE",
            ),
        )
    }

    @Test
    fun `country in display name is ready before metadata request finishes`() {
        assertEquals(
            "NL",
            resolveRefreshedServerCountry(
                serverName = "Нидерланды 2",
                freshCountry = null,
                fallbackCountry = null,
            ),
        )
    }

    @Test
    fun `leading profile flag is ready before metadata request finishes`() {
        assertEquals(
            "SE",
            resolveRefreshedServerCountry(
                serverName = "🇸🇪 Stockholm",
                freshCountry = null,
                fallbackCountry = null,
            ),
        )
    }

    @Test
    fun `fresh metadata fills generic server label`() {
        assertEquals(
            "FI",
            resolveRefreshedServerCountry(
                serverName = "Private node",
                freshCountry = "FI",
                fallbackCountry = null,
            ),
        )
    }
}
