package com.tobevpn.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays sequences taken from production diagnostic journals. Both shipped
 * regressions lived in this decision, so the scenarios are written from the
 * observed logs rather than from the implementation.
 */
class TunnelHealthEpisodeTest {

    private val loop = 7
    private val probeAt = 1_000_000L

    private fun evidence(
        bytes: Long,
        ageMs: Long,
        loopGeneration: Int = loop,
    ) = DownlinkEvidence(
        observedAtMs = if (bytes <= 0L) 0L else probeAt - ageMs,
        loopGeneration = if (bytes <= 0L) -1 else loopGeneration,
        bytes = bytes,
    )

    private fun TunnelHealthEpisode.probe(
        healthy: Boolean,
        evidence: DownlinkEvidence = evidence(bytes = 0L, ageMs = 0L),
        atMs: Long = probeAt,
    ) = onProbeResult(
        probeHealthy = healthy,
        probeStartedAtMs = atMs,
        probeLoopGeneration = loop,
        evidence = evidence,
    )

    // --- The 1.0.66 regression: a working session dropped every 20 minutes ---

    @Test
    fun `idle session losing one probe is not a verdict`() {
        // Journal 2026-08-12 20:50: all three control domains failed with TLS
        // while the phone was idle, so the pre-probe window held only a few
        // kilobytes — far below the liveness threshold. The old code treated
        // that single cycle as proof of death and killed a live tunnel.
        val episode = TunnelHealthEpisode()

        val decision = episode.probe(
            healthy = false,
            evidence = evidence(bytes = 2_048L, ageMs = 68L),
        )

        assertTrue(decision is TunnelHealthDecision.AwaitingConfirmation)
        assertEquals(1, episode.consecutiveFailures)
    }

    @Test
    fun `a transient disturbance that clears leaves the session alive`() {
        // Reconnecting 44 s later worked in the journal, so the interference
        // was brief: the confirmation probe one interval later succeeds.
        val episode = TunnelHealthEpisode()

        episode.probe(healthy = false, evidence = evidence(2_048L, 68L))
        val recovered = episode.probe(healthy = true)

        assertEquals(TunnelHealthDecision.Healthy, recovered)
        assertEquals(0, episode.consecutiveFailures)
    }

    // --- The original defect: Connected forever over a dead tunnel ---

    @Test
    fun `two separate failed cycles still confirm a dead tunnel`() {
        val episode = TunnelHealthEpisode()

        episode.probe(healthy = false)
        val verdict = episode.probe(healthy = false)

        assertTrue(verdict is TunnelHealthDecision.ConfirmedFailure)
        assertEquals(2, (verdict as TunnelHealthDecision.ConfirmedFailure).failures)
    }

    @Test
    fun `a dead tunnel cannot postpone the verdict indefinitely`() {
        val episode = TunnelHealthEpisode()
        var cycles = 0
        var decision: TunnelHealthDecision = TunnelHealthDecision.Healthy

        while (decision !is TunnelHealthDecision.ConfirmedFailure && cycles < 10) {
            decision = episode.probe(healthy = false)
            cycles++
        }

        assertTrue(decision is TunnelHealthDecision.ConfirmedFailure)
        assertEquals(TunnelHealthVerdictPolicy.REQUIRED_CONSECUTIVE_FAILURES, cycles)
    }

    @Test
    fun `an alternating tunnel never accumulates a verdict`() {
        // Deliberate: a link that answers every other cycle is degraded but
        // usable, and dropping it would be worse than keeping it.
        val episode = TunnelHealthEpisode()

        repeat(5) {
            assertTrue(episode.probe(healthy = false) is TunnelHealthDecision.AwaitingConfirmation)
            assertEquals(TunnelHealthDecision.Healthy, episode.probe(healthy = true))
        }

        assertEquals(0, episode.consecutiveFailures)
    }

    // --- Liveness evidence must stay honest (invariants from the audit) ---

    @Test
    fun `sufficient application downlink overrides a failed probe`() {
        val episode = TunnelHealthEpisode()

        val decision = episode.probe(
            healthy = false,
            evidence = evidence(bytes = 64L * 1024L, ageMs = 5_000L),
        )

        assertTrue(decision is TunnelHealthDecision.LivenessOverride)
        assertEquals(0, episode.consecutiveFailures)
    }

    @Test
    fun `an override resets a pending failure instead of confirming it`() {
        val episode = TunnelHealthEpisode()

        episode.probe(healthy = false)
        val overridden = episode.probe(
            healthy = false,
            evidence = evidence(bytes = 64L * 1024L, ageMs = 5_000L),
        )
        val afterOverride = episode.probe(healthy = false)

        assertTrue(overridden is TunnelHealthDecision.LivenessOverride)
        // Must be a fresh first strike, not the second one.
        assertTrue(afterOverride is TunnelHealthDecision.AwaitingConfirmation)
        assertEquals(1, episode.consecutiveFailures)
    }

    @Test
    fun `uplink only traffic cannot override a failed probe`() {
        val episode = TunnelHealthEpisode()

        val decision = episode.probe(healthy = false, evidence = evidence(bytes = 0L, ageMs = 0L))

        assertTrue(decision is TunnelHealthDecision.AwaitingConfirmation)
    }

    @Test
    fun `downlink from a replaced xray loop cannot override`() {
        val episode = TunnelHealthEpisode()

        val decision = episode.probe(
            healthy = false,
            evidence = evidence(bytes = 64L * 1024L, ageMs = 5_000L, loopGeneration = loop - 1),
        )

        assertTrue(decision is TunnelHealthDecision.AwaitingConfirmation)
    }

    @Test
    fun `downlink produced after the probe started cannot override`() {
        val episode = TunnelHealthEpisode()

        val decision = episode.onProbeResult(
            probeHealthy = false,
            probeStartedAtMs = probeAt,
            probeLoopGeneration = loop,
            evidence = DownlinkEvidence(
                observedAtMs = probeAt + 1L,
                loopGeneration = loop,
                bytes = 64L * 1024L,
            ),
        )

        assertTrue(decision is TunnelHealthDecision.AwaitingConfirmation)
    }

    @Test
    fun `downlink older than the grace window cannot override`() {
        val episode = TunnelHealthEpisode()

        val decision = episode.probe(
            healthy = false,
            evidence = evidence(
                bytes = 64L * 1024L,
                ageMs = TunnelLivenessPolicy.RECENT_DOWNLINK_GRACE_MS + 1L,
            ),
        )

        assertTrue(decision is TunnelHealthDecision.AwaitingConfirmation)
    }

    // --- The decision must report what it acted on ---

    @Test
    fun `every failing decision carries the evidence it judged`() {
        val episode = TunnelHealthEpisode()

        val pending = episode.probe(
            healthy = false,
            evidence = evidence(bytes = 2_048L, ageMs = 68L),
        ) as TunnelHealthDecision.AwaitingConfirmation
        val verdict = episode.probe(
            healthy = false,
            evidence = evidence(bytes = 1_024L, ageMs = 900L),
        ) as TunnelHealthDecision.ConfirmedFailure

        assertEquals(2_048L, pending.downlinkBytes)
        assertEquals(68L, pending.downlinkAgeMs)
        assertEquals(1_024L, verdict.downlinkBytes)
        assertEquals(900L, verdict.downlinkAgeMs)
    }

    @Test
    fun `absent downlink is reported as unknown rather than zero age`() {
        val episode = TunnelHealthEpisode()

        val decision = episode.probe(healthy = false) as TunnelHealthDecision.AwaitingConfirmation

        assertEquals(0L, decision.downlinkBytes)
        assertEquals(-1L, decision.downlinkAgeMs)
    }
}
