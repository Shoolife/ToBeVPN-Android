package com.tobevpn.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkAvailabilityDeadlineTest {

    @Test
    fun `deadline expires exactly at timeout boundary`() {
        val deadline = NetworkAvailabilityDeadline(startedAtMs = 1_000L, timeoutMs = 15_000L)

        assertFalse(deadline.isExpired(15_999L))
        assertTrue(deadline.isExpired(16_000L))
    }

    @Test
    fun `physical network loss uses policy deadline`() {
        val deadline = NetworkAvailabilityDeadline(
            startedAtMs = 1_000L,
            timeoutMs = requireNotNull(
                UnderlyingNetworkPolicy.teardownTimeoutMs(
                    UnderlyingNetworkAvailability.UNAVAILABLE,
                ),
            ),
        )

        assertFalse(deadline.isExpired(15_999L))
        assertTrue(deadline.isExpired(16_000L))
    }

    @Test
    fun `next check never sleeps past deadline`() {
        val deadline = NetworkAvailabilityDeadline(startedAtMs = 10_000L, timeoutMs = 15_000L)

        assertEquals(5_000L, deadline.nextCheckDelayMs(nowMs = 10_000L, maximumDelayMs = 5_000L))
        assertEquals(750L, deadline.nextCheckDelayMs(nowMs = 24_250L, maximumDelayMs = 5_000L))
    }

    @Test
    fun `clock value before start does not expire deadline`() {
        val deadline = NetworkAvailabilityDeadline(startedAtMs = 10_000L, timeoutMs = 15_000L)

        assertFalse(deadline.isExpired(9_000L))
        assertEquals(5_000L, deadline.nextCheckDelayMs(nowMs = 9_000L, maximumDelayMs = 5_000L))
    }

    @Test
    fun `non-positive timeout is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkAvailabilityDeadline(startedAtMs = 0L, timeoutMs = 0L)
        }
    }

    @Test
    fun `expired deadline still returns a positive non-blocking delay`() {
        val deadline = NetworkAvailabilityDeadline(startedAtMs = 0L, timeoutMs = 15_000L)

        assertTrue(deadline.isExpired(15_000L))
        assertEquals(1L, deadline.nextCheckDelayMs(nowMs = 15_000L, maximumDelayMs = 5_000L))
    }

    @Test
    fun `non-positive maximum delay is rejected`() {
        val deadline = NetworkAvailabilityDeadline(startedAtMs = 0L, timeoutMs = 15_000L)

        assertThrows(IllegalArgumentException::class.java) {
            deadline.nextCheckDelayMs(nowMs = 0L, maximumDelayMs = 0L)
        }
    }
}
