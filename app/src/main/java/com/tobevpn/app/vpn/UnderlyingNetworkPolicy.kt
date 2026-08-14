package com.tobevpn.app.vpn

/**
 * Distinguishes a complete loss of physical connectivity from Android's
 * general-internet validation state. An unvalidated carrier network can still
 * carry a working VPN tunnel (for example on an operator allowlist), so only
 * complete physical-network loss receives a teardown deadline.
 */
internal enum class UnderlyingNetworkAvailability {
    VALIDATED,
    UNVALIDATED,
    UNAVAILABLE,
}

internal object UnderlyingNetworkPolicy {
    const val NO_NETWORK_TIMEOUT_MS = 15_000L

    fun canAttemptTunnelProbe(availability: UnderlyingNetworkAvailability): Boolean =
        availability != UnderlyingNetworkAvailability.UNAVAILABLE

    fun teardownTimeoutMs(availability: UnderlyingNetworkAvailability): Long? =
        NO_NETWORK_TIMEOUT_MS.takeIf {
            availability == UnderlyingNetworkAvailability.UNAVAILABLE
        }
}
