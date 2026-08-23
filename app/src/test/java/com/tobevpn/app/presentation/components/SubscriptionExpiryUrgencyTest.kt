package com.tobevpn.app.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionExpiryUrgencyTest {
    @Test
    fun `more than seven days is neutral`() {
        assertEquals(
            SubscriptionExpiryUrgency.NORMAL,
            subscriptionExpiryUrgency(
                NOW + SEVEN_DAYS_MS + 1L,
                NOW,
            ),
        )
    }

    @Test
    fun `exactly seven days starts warning`() {
        assertEquals(
            SubscriptionExpiryUrgency.WARNING,
            subscriptionExpiryUrgency(
                NOW + SEVEN_DAYS_MS,
                NOW,
            ),
        )
    }

    @Test
    fun `more than three days remains warning`() {
        assertEquals(
            SubscriptionExpiryUrgency.WARNING,
            subscriptionExpiryUrgency(
                NOW + THREE_DAYS_MS + 1L,
                NOW,
            ),
        )
    }

    @Test
    fun `exactly three days starts critical`() {
        assertEquals(
            SubscriptionExpiryUrgency.CRITICAL,
            subscriptionExpiryUrgency(
                NOW + THREE_DAYS_MS,
                NOW,
            ),
        )
    }

    @Test
    fun `past expiry remains critical`() {
        assertEquals(
            SubscriptionExpiryUrgency.CRITICAL,
            subscriptionExpiryUrgency(
                NOW - 1L,
                NOW,
            ),
        )
    }

    private companion object {
        const val NOW = 2_000_000_000_000L
        const val DAY_MS = 86_400_000L
        const val THREE_DAYS_MS = 3L * DAY_MS
        const val SEVEN_DAYS_MS = 7L * DAY_MS
    }
}
