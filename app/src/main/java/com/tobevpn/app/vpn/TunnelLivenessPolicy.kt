package com.tobevpn.app.vpn

/**
 * Decides whether application traffic may override a failed end-to-end probe.
 *
 * Only downlink observed before the probe started is accepted. Uplink alone
 * merely proves that the device handed bytes to Xray; it does not prove that
 * the server returned anything. Taking the evidence before the probe also
 * prevents the watchdog's own requests from confirming themselves.
 */
internal object TunnelLivenessPolicy {
    // Evidence must not survive a complete 30-second watchdog interval. It is
    // also consumed once by VpnConnectionManager, so a previous probe can
    // never become evidence for a later probe.
    const val RECENT_DOWNLINK_GRACE_MS = 25_000L
    const val MIN_DOWNLINK_BYTES = 16L * 1024L

    fun hasSufficientRecentDownlinkBeforeProbe(
        probeStartedAtMs: Long,
        probeLoopGeneration: Int,
        lastDownlinkAtMs: Long,
        lastDownlinkLoopGeneration: Int,
        downlinkBytes: Long,
    ): Boolean {
        if (probeStartedAtMs <= 0L || lastDownlinkAtMs <= 0L) return false
        if (downlinkBytes < MIN_DOWNLINK_BYTES) return false
        if (probeLoopGeneration <= 0 || lastDownlinkLoopGeneration != probeLoopGeneration) {
            return false
        }
        if (lastDownlinkAtMs > probeStartedAtMs) return false
        return probeStartedAtMs - lastDownlinkAtMs <= RECENT_DOWNLINK_GRACE_MS
    }
}
