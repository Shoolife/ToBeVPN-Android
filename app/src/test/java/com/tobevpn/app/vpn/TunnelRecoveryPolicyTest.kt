package com.tobevpn.app.vpn

import com.tobevpn.app.domain.model.ServerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelRecoveryPolicyTest {

    @Test
    fun `automatic fingerprint fallback preserves alternative server budget`() {
        assertFalse(
            TunnelRecoveryPolicy.fingerprintRetryConsumesAttempt(
                automaticSelection = true,
            ),
        )
    }

    @Test
    fun `manual fingerprint fallback replaces bounded same server retry`() {
        assertTrue(
            TunnelRecoveryPolicy.fingerprintRetryConsumesAttempt(
                automaticSelection = false,
            ),
        )
    }

    @Test
    fun `manual selection permits one startup retry of the same server`() {
        assertEquals(
            1,
            TunnelRecoveryPolicy.maxAttempts(
                automaticSelection = false,
                duringStartup = true,
            ),
        )
        assertTrue(
            TunnelRecoveryPolicy.canAttempt(
                currentAttempts = 0,
                automaticSelection = false,
                duringStartup = true,
            ),
        )
        assertFalse(
            TunnelRecoveryPolicy.canAttempt(
                currentAttempts = 1,
                automaticSelection = false,
                duringStartup = true,
            ),
        )
    }

    @Test
    fun `manual selection never permits watchdog recovery`() {
        assertEquals(
            0,
            TunnelRecoveryPolicy.maxAttempts(
                automaticSelection = false,
                duringStartup = false,
            ),
        )
        assertFalse(
            TunnelRecoveryPolicy.canAttempt(
                currentAttempts = 0,
                automaticSelection = false,
                duringStartup = false,
            ),
        )
    }

    @Test
    fun `automatic selection may try two alternative recovery passes`() {
        assertEquals(
            2,
            TunnelRecoveryPolicy.maxAttempts(
                automaticSelection = true,
                duringStartup = false,
            ),
        )
        assertTrue(TunnelRecoveryPolicy.canAttempt(0, true, duringStartup = false))
        assertTrue(TunnelRecoveryPolicy.canAttempt(1, true, duringStartup = true))
        assertFalse(TunnelRecoveryPolicy.canAttempt(2, true, duringStartup = false))
    }

    @Test
    fun `automatic bypass selection walks through one more candidate`() {
        assertEquals(
            3,
            TunnelRecoveryPolicy.maxAttempts(
                automaticSelection = true,
                duringStartup = true,
                source = ServerSource.BASE_STATION_BYPASS,
            ),
        )
        assertTrue(
            TunnelRecoveryPolicy.canAttempt(
                currentAttempts = 2,
                automaticSelection = true,
                duringStartup = true,
                source = ServerSource.BASE_STATION_BYPASS,
            ),
        )
        assertFalse(
            TunnelRecoveryPolicy.canAttempt(
                currentAttempts = 3,
                automaticSelection = true,
                duringStartup = true,
                source = ServerSource.BASE_STATION_BYPASS,
            ),
        )
    }

    @Test
    fun `the wider bypass budget never leaks into manual selection`() {
        assertEquals(
            0,
            TunnelRecoveryPolicy.maxAttempts(
                automaticSelection = false,
                duringStartup = false,
                source = ServerSource.BASE_STATION_BYPASS,
            ),
        )
        assertEquals(
            1,
            TunnelRecoveryPolicy.maxAttempts(
                automaticSelection = false,
                duringStartup = true,
                source = ServerSource.BASE_STATION_BYPASS,
            ),
        )
    }
}
