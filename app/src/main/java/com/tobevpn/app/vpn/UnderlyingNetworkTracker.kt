package com.tobevpn.app.vpn

/**
 * Tracks the single Android network currently carrying Xray's upstream.
 *
 * Registering a [android.net.ConnectivityManager.NetworkCallback] immediately
 * emits the already-active network. That first value is a baseline, not a
 * handover. A network that appears after the tracked one was lost is a real
 * handover even if a vendor happens to reuse an equal handle.
 */
internal class UnderlyingNetworkTracker<T> {
    private var current: T? = null
    private var currentWasLost = false

    @Synchronized
    fun onAvailable(network: T): Availability {
        val previous = current
        val availability = when {
            previous == null -> Availability.INITIAL
            previous != network || currentWasLost -> Availability.HANDOVER
            else -> Availability.UNCHANGED
        }
        current = network
        currentWasLost = false
        return availability
    }

    @Synchronized
    fun onLost(network: T): Boolean {
        if (current != network) return false
        currentWasLost = true
        return true
    }

    @Synchronized
    fun isCurrent(network: T): Boolean = current == network

    @Synchronized
    fun isAvailable(network: T): Boolean = current == network && !currentWasLost

    @Synchronized
    fun reset() {
        current = null
        currentWasLost = false
    }

    enum class Availability {
        INITIAL,
        UNCHANGED,
        HANDOVER,
    }
}
