package com.tobevpn.app.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelLivenessPolicyTest {

    @Test
    fun `recent downlink from active loop confirms liveness`() {
        assertTrue(
            TunnelLivenessPolicy.hasSufficientRecentDownlinkBeforeProbe(
                probeStartedAtMs = 100_000L,
                probeLoopGeneration = 7,
                lastDownlinkAtMs = 99_000L,
                lastDownlinkLoopGeneration = 7,
                downlinkBytes = 32L * 1024L,
            ),
        )
    }

    @Test
    fun `outgoing-only activity cannot confirm liveness`() {
        assertFalse(
            TunnelLivenessPolicy.hasSufficientRecentDownlinkBeforeProbe(
                probeStartedAtMs = 100_000L,
                probeLoopGeneration = 7,
                lastDownlinkAtMs = 0L,
                lastDownlinkLoopGeneration = -1,
                downlinkBytes = 0L,
            ),
        )
    }

    @Test
    fun `downlink produced after probe start cannot confirm that probe`() {
        assertFalse(
            TunnelLivenessPolicy.hasSufficientRecentDownlinkBeforeProbe(
                probeStartedAtMs = 100_000L,
                probeLoopGeneration = 7,
                lastDownlinkAtMs = 100_001L,
                lastDownlinkLoopGeneration = 7,
                downlinkBytes = 32L * 1024L,
            ),
        )
    }

    @Test
    fun `downlink from previous Xray loop cannot validate replacement loop`() {
        assertFalse(
            TunnelLivenessPolicy.hasSufficientRecentDownlinkBeforeProbe(
                probeStartedAtMs = 100_000L,
                probeLoopGeneration = 8,
                lastDownlinkAtMs = 99_000L,
                lastDownlinkLoopGeneration = 7,
                downlinkBytes = 32L * 1024L,
            ),
        )
    }

    @Test
    fun `grace boundary is inclusive`() {
        assertTrue(
            TunnelLivenessPolicy.hasSufficientRecentDownlinkBeforeProbe(
                probeStartedAtMs = 100_000L,
                probeLoopGeneration = 7,
                lastDownlinkAtMs = 75_000L,
                lastDownlinkLoopGeneration = 7,
                downlinkBytes = 16L * 1024L,
            ),
        )
    }

    @Test
    fun `stale downlink cannot hide a failed probe`() {
        assertFalse(
            TunnelLivenessPolicy.hasSufficientRecentDownlinkBeforeProbe(
                probeStartedAtMs = 100_001L,
                probeLoopGeneration = 7,
                lastDownlinkAtMs = 75_000L,
                lastDownlinkLoopGeneration = 7,
                downlinkBytes = 32L * 1024L,
            ),
        )
    }

    @Test
    fun `downlink from a previous watchdog interval is stale`() {
        assertFalse(
            TunnelLivenessPolicy.hasSufficientRecentDownlinkBeforeProbe(
                probeStartedAtMs = 100_000L,
                probeLoopGeneration = 7,
                lastDownlinkAtMs = 70_000L,
                lastDownlinkLoopGeneration = 7,
                downlinkBytes = 32L * 1024L,
            ),
        )
    }

    @Test
    fun `small transport response cannot override failed end to end probe`() {
        assertFalse(
            TunnelLivenessPolicy.hasSufficientRecentDownlinkBeforeProbe(
                probeStartedAtMs = 100_000L,
                probeLoopGeneration = 7,
                lastDownlinkAtMs = 99_000L,
                lastDownlinkLoopGeneration = 7,
                downlinkBytes = 512L,
            ),
        )
    }
}
