package com.tobevpn.app.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeDownlinkEvidenceGateTest {

    @Test
    fun `all counter drains during probe are excluded`() {
        val gate = ProbeDownlinkEvidenceGate()

        gate.onProbeStarted()

        assertTrue(gate.suppressEvidenceForCurrentDrain())
        assertTrue(gate.suppressEvidenceForCurrentDrain())
    }

    @Test
    fun `exactly first counter drain after probe is excluded`() {
        val gate = ProbeDownlinkEvidenceGate()

        gate.onProbeStarted()
        gate.onProbeFinished()

        assertTrue(gate.suppressEvidenceForCurrentDrain())
        assertFalse(gate.suppressEvidenceForCurrentDrain())
    }

    @Test
    fun `reset accepts ordinary application downlink`() {
        val gate = ProbeDownlinkEvidenceGate()
        gate.onProbeStarted()

        gate.reset()

        assertFalse(gate.suppressEvidenceForCurrentDrain())
    }
}
