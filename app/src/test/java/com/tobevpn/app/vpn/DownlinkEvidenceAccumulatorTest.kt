package com.tobevpn.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class DownlinkEvidenceAccumulatorTest {

    @Test
    fun `evidence is consumed exactly once`() {
        val accumulator = DownlinkEvidenceAccumulator()
        accumulator.record(observedAtMs = 10_000L, loopGeneration = 7, bytes = 32_000L)

        assertEquals(
            DownlinkEvidence(observedAtMs = 10_000L, loopGeneration = 7, bytes = 32_000L),
            accumulator.consume(),
        )
        assertEquals(DownlinkEvidence(), accumulator.consume())
    }

    @Test
    fun `same loop accumulates while replacement loop starts a new interval`() {
        val accumulator = DownlinkEvidenceAccumulator()
        accumulator.record(observedAtMs = 10_000L, loopGeneration = 7, bytes = 8_000L)
        accumulator.record(observedAtMs = 11_000L, loopGeneration = 7, bytes = 9_000L)

        assertEquals(
            DownlinkEvidence(observedAtMs = 11_000L, loopGeneration = 7, bytes = 17_000L),
            accumulator.consume(),
        )

        accumulator.record(observedAtMs = 12_000L, loopGeneration = 8, bytes = 5_000L)
        assertEquals(
            DownlinkEvidence(observedAtMs = 12_000L, loopGeneration = 8, bytes = 5_000L),
            accumulator.consume(),
        )
    }
}
