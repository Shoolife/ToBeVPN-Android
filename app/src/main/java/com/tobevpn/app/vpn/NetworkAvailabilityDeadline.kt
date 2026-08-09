package com.tobevpn.app.vpn

/**
 * Monotonic deadline used while a live TUN is waiting for a usable physical
 * network. Keeping this calculation outside Android code makes the exact
 * timeout boundary deterministic and unit-testable.
 */
internal class NetworkAvailabilityDeadline(
    private val startedAtMs: Long,
    private val timeoutMs: Long,
) {
    init {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
    }

    fun isExpired(nowMs: Long): Boolean = elapsedMs(nowMs) >= timeoutMs

    fun nextCheckDelayMs(nowMs: Long, maximumDelayMs: Long): Long {
        require(maximumDelayMs > 0L) { "maximumDelayMs must be positive" }
        val remainingMs = (timeoutMs - elapsedMs(nowMs)).coerceAtLeast(0L)
        return minOf(maximumDelayMs, remainingMs).coerceAtLeast(1L)
    }

    private fun elapsedMs(nowMs: Long): Long = (nowMs - startedAtMs).coerceAtLeast(0L)
}
