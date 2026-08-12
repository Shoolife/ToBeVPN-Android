package com.tobevpn.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class UnderlyingNetworkPolicyTest {

    @Test
    fun `complete physical network loss keeps the existing fifteen second deadline`() {
        assertEquals(
            15_000L,
            UnderlyingNetworkPolicy.timeoutMs(UnderlyingNetworkAvailability.UNAVAILABLE),
        )
    }

    @Test
    fun `internet network awaiting validation gets a longer deadline`() {
        assertEquals(
            45_000L,
            UnderlyingNetworkPolicy.timeoutMs(UnderlyingNetworkAvailability.UNVALIDATED),
        )
    }

    @Test
    fun `validated network needs no teardown deadline`() {
        assertEquals(
            0L,
            UnderlyingNetworkPolicy.timeoutMs(UnderlyingNetworkAvailability.VALIDATED),
        )
    }
}
