package com.tobevpn.app.vpn

/**
 * Distinguishes a complete loss of physical connectivity from Android's
 * temporary internet-validation state. A TUN must not be kept indefinitely,
 * but captive-portal checks and Wi-Fi revalidation need more than 15 seconds.
 */
internal enum class UnderlyingNetworkAvailability {
    VALIDATED,
    UNVALIDATED,
    UNAVAILABLE,
}

internal object UnderlyingNetworkPolicy {
    const val NO_NETWORK_TIMEOUT_MS = 15_000L
    const val UNVALIDATED_NETWORK_TIMEOUT_MS = 45_000L

    fun timeoutMs(availability: UnderlyingNetworkAvailability): Long = when (availability) {
        UnderlyingNetworkAvailability.VALIDATED -> 0L
        UnderlyingNetworkAvailability.UNVALIDATED -> UNVALIDATED_NETWORK_TIMEOUT_MS
        UnderlyingNetworkAvailability.UNAVAILABLE -> NO_NETWORK_TIMEOUT_MS
    }
}
