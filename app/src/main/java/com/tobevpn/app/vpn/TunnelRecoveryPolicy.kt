package com.tobevpn.app.vpn

import com.tobevpn.app.domain.model.ServerSource

/**
 * Bounds consecutive transparent reloads in one unhealthy episode. Manual
 * selection permits one startup retry of the same server, but never permits a
 * watchdog-driven reload after the connection has already been validated.
 *
 * The public bypass pool is large and intermittent — the same profile can
 * refuse traffic and work again minutes later — so automatic selection there
 * is allowed to walk through more candidates than the curated panel list.
 */
internal object TunnelRecoveryPolicy {
    const val MANUAL_WATCHDOG_MAX_ATTEMPTS = 0
    const val MANUAL_STARTUP_MAX_ATTEMPTS = 1
    const val AUTOMATIC_MAX_ATTEMPTS = 2
    const val AUTOMATIC_BYPASS_MAX_ATTEMPTS = 3

    fun maxAttempts(
        automaticSelection: Boolean,
        duringStartup: Boolean,
        source: ServerSource = ServerSource.STANDARD,
    ): Int = when {
        automaticSelection && source == ServerSource.BASE_STATION_BYPASS ->
            AUTOMATIC_BYPASS_MAX_ATTEMPTS
        automaticSelection -> AUTOMATIC_MAX_ATTEMPTS
        duringStartup -> MANUAL_STARTUP_MAX_ATTEMPTS
        else -> MANUAL_WATCHDOG_MAX_ATTEMPTS
    }

    fun canAttempt(
        currentAttempts: Int,
        automaticSelection: Boolean,
        duringStartup: Boolean,
        source: ServerSource = ServerSource.STANDARD,
    ): Boolean = currentAttempts < maxAttempts(automaticSelection, duringStartup, source)

    /**
     * AUTO receives one separately bounded fingerprint fallback without
     * sacrificing its existing alternative-server attempts. In MANUAL the
     * fingerprint change replaces the existing one same-server startup retry.
     */
    fun fingerprintRetryConsumesAttempt(automaticSelection: Boolean): Boolean =
        !automaticSelection
}
