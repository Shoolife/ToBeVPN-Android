package com.tobevpn.app.vpn

/**
 * Process-local rolling-window limit for automatic reconnects after the
 * physical network disappears. Manual connection attempts do not pass through
 * this limiter and therefore remain available after the automatic budget is
 * exhausted.
 */
internal class NetworkResumeRateLimiter(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    private val attempts = ArrayDeque<Long>()

    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(windowMs > 0L) { "windowMs must be positive" }
    }

    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        val cutoffMs = nowMs - windowMs
        while (attempts.firstOrNull()?.let { it <= cutoffMs } == true) {
            attempts.removeFirst()
        }
        if (attempts.size >= maxAttempts) return false
        attempts.addLast(nowMs)
        return true
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 5
        const val DEFAULT_WINDOW_MS = 60L * 60L * 1_000L
    }
}
