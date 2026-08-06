package com.tobevpn.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelRecoveryPolicyTest {

    @Test
    fun `manual selection permits exactly one transparent retry`() {
        assertEquals(1, TunnelRecoveryPolicy.maxAttempts(automaticSelection = false))
        assertTrue(TunnelRecoveryPolicy.canAttempt(0, automaticSelection = false))
        assertFalse(TunnelRecoveryPolicy.canAttempt(1, automaticSelection = false))
    }

    @Test
    fun `automatic selection may try two alternative recovery passes`() {
        assertEquals(2, TunnelRecoveryPolicy.maxAttempts(automaticSelection = true))
        assertTrue(TunnelRecoveryPolicy.canAttempt(0, automaticSelection = true))
        assertTrue(TunnelRecoveryPolicy.canAttempt(1, automaticSelection = true))
        assertFalse(TunnelRecoveryPolicy.canAttempt(2, automaticSelection = true))
    }
}
