package com.tobevpn.app.vpn

/**
 * Bounds consecutive transparent reloads in one unhealthy episode. A healthy
 * probe or confirmed traffic resets the episode in VpnConnectionManager.
 */
internal object TunnelRecoveryPolicy {
    const val MANUAL_MAX_ATTEMPTS = 1
    const val AUTOMATIC_MAX_ATTEMPTS = 2

    fun maxAttempts(automaticSelection: Boolean): Int =
        if (automaticSelection) AUTOMATIC_MAX_ATTEMPTS else MANUAL_MAX_ATTEMPTS

    fun canAttempt(currentAttempts: Int, automaticSelection: Boolean): Boolean =
        currentAttempts < maxAttempts(automaticSelection)
}
