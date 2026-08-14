package com.tobevpn.app.vpn

/**
 * The watchdog's decision for one probe cycle.
 *
 * Carrying the numbers that drove the decision keeps the journal honest: the
 * caller logs exactly what this class saw, so a production log can be replayed
 * against [TunnelHealthEpisode] in a unit test.
 */
internal sealed interface TunnelHealthDecision {
    /** The end-to-end probe succeeded. */
    data object Healthy : TunnelHealthDecision

    /** The probe failed, but application downlink proves the tunnel carries traffic. */
    data class LivenessOverride(
        val downlinkBytes: Long,
        val downlinkAgeMs: Long,
    ) : TunnelHealthDecision

    /** The probe failed without proof of liveness, but one cycle is not a verdict. */
    data class AwaitingConfirmation(
        val failures: Int,
        val required: Int,
        val downlinkBytes: Long,
        val downlinkAgeMs: Long,
    ) : TunnelHealthDecision

    /** Enough separate cycles failed; the tunnel is treated as dead. */
    data class ConfirmedFailure(
        val failures: Int,
        val downlinkBytes: Long,
        val downlinkAgeMs: Long,
    ) : TunnelHealthDecision
}

/**
 * All the state one watchdog episode needs to turn probe results into a
 * verdict. Deliberately free of Android and coroutines so the exact sequences
 * observed in production journals can be replayed in tests — both regressions
 * this project shipped lived in this decision, not in the rules it applies.
 *
 * One instance belongs to one watchdog job: a reload, a network handover or a
 * reconnect starts a new job and therefore a new episode.
 */
internal class TunnelHealthEpisode {
    var consecutiveFailures: Int = 0
        private set

    fun onProbeResult(
        probeHealthy: Boolean,
        probeStartedAtMs: Long,
        probeLoopGeneration: Int,
        evidence: DownlinkEvidence,
    ): TunnelHealthDecision {
        val downlinkAgeMs = downlinkAgeMs(evidence.observedAtMs, probeStartedAtMs)
        if (probeHealthy) {
            consecutiveFailures = 0
            return TunnelHealthDecision.Healthy
        }

        val livenessProven = TunnelLivenessPolicy.hasSufficientRecentDownlinkBeforeProbe(
            probeStartedAtMs = probeStartedAtMs,
            probeLoopGeneration = probeLoopGeneration,
            lastDownlinkAtMs = evidence.observedAtMs,
            lastDownlinkLoopGeneration = evidence.loopGeneration,
            downlinkBytes = evidence.bytes,
        )
        if (livenessProven) {
            // Traffic is flowing, so the probe — not the tunnel — is what
            // failed. The episode restarts from a known-good state.
            consecutiveFailures = 0
            return TunnelHealthDecision.LivenessOverride(
                downlinkBytes = evidence.bytes,
                downlinkAgeMs = downlinkAgeMs,
            )
        }

        consecutiveFailures++
        return if (TunnelHealthVerdictPolicy.isConfirmedFailure(consecutiveFailures)) {
            TunnelHealthDecision.ConfirmedFailure(
                failures = consecutiveFailures,
                downlinkBytes = evidence.bytes,
                downlinkAgeMs = downlinkAgeMs,
            )
        } else {
            TunnelHealthDecision.AwaitingConfirmation(
                failures = consecutiveFailures,
                required = TunnelHealthVerdictPolicy.REQUIRED_CONSECUTIVE_FAILURES,
                downlinkBytes = evidence.bytes,
                downlinkAgeMs = downlinkAgeMs,
            )
        }
    }

    private fun downlinkAgeMs(observedAtMs: Long, probeStartedAtMs: Long): Long =
        if (observedAtMs <= 0L) -1L else (probeStartedAtMs - observedAtMs).coerceAtLeast(0L)
}
