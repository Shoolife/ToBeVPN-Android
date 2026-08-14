package com.tobevpn.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnderlyingNetworkPolicyTest {

    @Test
    fun `complete physical network loss keeps the existing fifteen second deadline`() {
        val timeoutMs = requireNotNull(
            UnderlyingNetworkPolicy.teardownTimeoutMs(
                UnderlyingNetworkAvailability.UNAVAILABLE,
            ),
        )

        assertEquals(
            15_000L,
            timeoutMs,
        )
        assertFalse(
            UnderlyingNetworkPolicy.canAttemptTunnelProbe(
                UnderlyingNetworkAvailability.UNAVAILABLE,
            ),
        )
    }

    @Test
    fun `unvalidated carrier network can prove tunnel liveness without teardown deadline`() {
        assertNull(
            UnderlyingNetworkPolicy.teardownTimeoutMs(
                UnderlyingNetworkAvailability.UNVALIDATED,
            ),
        )
        assertTrue(
            UnderlyingNetworkPolicy.canAttemptTunnelProbe(
                UnderlyingNetworkAvailability.UNVALIDATED,
            ),
        )
    }

    @Test
    fun `validated network needs no teardown deadline`() {
        assertNull(
            UnderlyingNetworkPolicy.teardownTimeoutMs(
                UnderlyingNetworkAvailability.VALIDATED,
            ),
        )
        assertTrue(
            UnderlyingNetworkPolicy.canAttemptTunnelProbe(
                UnderlyingNetworkAvailability.VALIDATED,
            ),
        )
    }
}
