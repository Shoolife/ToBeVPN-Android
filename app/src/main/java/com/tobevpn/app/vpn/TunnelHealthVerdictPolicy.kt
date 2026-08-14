package com.tobevpn.app.vpn

/**
 * Decides when a failing watchdog probe becomes a verdict that the tunnel is
 * dead. This is deliberately a separate, documented rule because the project
 * has already oscillated between two opposite failures:
 *
 *  * treating any recent traffic as proof of liveness — the app kept showing
 *    Connected for ten minutes over a tunnel that carried nothing;
 *  * treating a single failed probe as proof of death — a transient TLS
 *    failure against all three control domains dropped a working session
 *    every twenty minutes.
 *
 * Both extremes come from acting on evidence gathered at one instant. The rule
 * here is therefore about *time separation*: a verdict needs failures from two
 * separate probe cycles, one full watchdog interval apart. A brief network or
 * DPI disturbance does not survive that gap; a genuinely dead tunnel does.
 *
 * The cost is bounded and intentional: one extra interval before a real
 * failure is acted upon. Detection stays inside the ceiling the audit set,
 * because a failed cycle short-circuits on a terminal TLS category.
 */
internal object TunnelHealthVerdictPolicy {
    /**
     * Cycles that must fail back to back. Two is the smallest value that
     * requires evidence from separate points in time; larger values buy little
     * and push detection past the acceptable ceiling.
     */
    const val REQUIRED_CONSECUTIVE_FAILURES = 2

    /**
     * A healthy probe — or application downlink accepted as proof of liveness
     * — ends the episode, so counting always restarts from a known-good state.
     */
    fun isConfirmedFailure(consecutiveFailures: Int): Boolean =
        consecutiveFailures >= REQUIRED_CONSECUTIVE_FAILURES

    /** Remaining confirmations before the watchdog may act. Never negative. */
    fun remainingConfirmations(consecutiveFailures: Int): Int =
        (REQUIRED_CONSECUTIVE_FAILURES - consecutiveFailures).coerceAtLeast(0)
}
