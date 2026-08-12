package com.tobevpn.app.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkResumeRateLimiterTest {

    @Test
    fun `only five automatic resumes are allowed inside one hour`() {
        val limiter = NetworkResumeRateLimiter(maxAttempts = 5, windowMs = 3_600_000L)

        repeat(5) { attempt ->
            assertTrue(limiter.tryAcquire(nowMs = attempt * 1_000L))
        }
        assertFalse(limiter.tryAcquire(nowMs = 5_000L))
    }

    @Test
    fun `oldest attempt expires at the rolling window boundary`() {
        val limiter = NetworkResumeRateLimiter(maxAttempts = 2, windowMs = 1_000L)

        assertTrue(limiter.tryAcquire(nowMs = 100L))
        assertTrue(limiter.tryAcquire(nowMs = 200L))
        assertFalse(limiter.tryAcquire(nowMs = 1_099L))
        assertTrue(limiter.tryAcquire(nowMs = 1_100L))
    }

    @Test
    fun `backward clock value cannot release attempts early`() {
        val limiter = NetworkResumeRateLimiter(maxAttempts = 1, windowMs = 1_000L)

        assertTrue(limiter.tryAcquire(nowMs = 5_000L))
        assertFalse(limiter.tryAcquire(nowMs = 4_000L))
        assertTrue(limiter.tryAcquire(nowMs = 6_000L))
    }
}
