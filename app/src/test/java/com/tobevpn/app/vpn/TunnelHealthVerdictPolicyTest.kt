package com.tobevpn.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelHealthVerdictPolicyTest {

    @Test
    fun `a single failed probe is never a verdict`() {
        // Guards the regression seen in production: one transient all-TLS
        // probe failure dropped a session that was still carrying downlink.
        assertFalse(TunnelHealthVerdictPolicy.isConfirmedFailure(0))
        assertFalse(TunnelHealthVerdictPolicy.isConfirmedFailure(1))
    }

    @Test
    fun `two consecutive failures confirm the verdict`() {
        // Guards the opposite regression: the app must not keep showing
        // Connected indefinitely once failures genuinely persist.
        assertTrue(TunnelHealthVerdictPolicy.isConfirmedFailure(2))
        assertTrue(TunnelHealthVerdictPolicy.isConfirmedFailure(3))
    }

    @Test
    fun `remaining confirmations counts down and never goes negative`() {
        assertEquals(2, TunnelHealthVerdictPolicy.remainingConfirmations(0))
        assertEquals(1, TunnelHealthVerdictPolicy.remainingConfirmations(1))
        assertEquals(0, TunnelHealthVerdictPolicy.remainingConfirmations(2))
        assertEquals(0, TunnelHealthVerdictPolicy.remainingConfirmations(5))
    }

    @Test
    fun `the requirement stays bounded so detection cannot stall`() {
        // A larger value would push worst-case detection past the ceiling the
        // audit set for a hung tunnel.
        assertTrue(TunnelHealthVerdictPolicy.REQUIRED_CONSECUTIVE_FAILURES >= 2)
        assertTrue(TunnelHealthVerdictPolicy.REQUIRED_CONSECUTIVE_FAILURES <= 3)
    }
}
