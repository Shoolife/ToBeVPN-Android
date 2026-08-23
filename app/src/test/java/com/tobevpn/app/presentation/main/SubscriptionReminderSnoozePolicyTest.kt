package com.tobevpn.app.presentation.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionReminderSnoozePolicyTest {
    @Test
    fun `later snoozes reminder for twelve hours`() {
        assertEquals(
            NOW + TWELVE_HOURS_MS,
            subscriptionReminderSnoozeUntil(NOW, NOW + TWO_DAYS_MS),
        )
    }

    @Test
    fun `snooze never crosses active subscription expiry`() {
        val expiry = NOW + TWO_HOURS_MS
        assertEquals(expiry, subscriptionReminderSnoozeUntil(NOW, expiry))
    }

    @Test
    fun `expired reminder can still be snoozed for twelve hours`() {
        assertEquals(
            NOW + TWELVE_HOURS_MS,
            subscriptionReminderSnoozeUntil(NOW, NOW - 1L),
        )
    }

    @Test
    fun `matching active snooze hides reminder`() {
        assertTrue(
            isSubscriptionReminderSnoozed(
                snoozedUntilMillis = NOW + 1L,
                snoozedForExpiryMillis = EXPIRY,
                currentExpiryMillis = EXPIRY,
                nowMillis = NOW,
            ),
        )
    }

    @Test
    fun `renewed subscription invalidates old snooze`() {
        assertFalse(
            isSubscriptionReminderSnoozed(
                snoozedUntilMillis = NOW + TWELVE_HOURS_MS,
                snoozedForExpiryMillis = EXPIRY,
                currentExpiryMillis = EXPIRY + TWO_DAYS_MS,
                nowMillis = NOW,
            ),
        )
    }

    @Test
    fun `elapsed snooze shows reminder again`() {
        assertFalse(
            isSubscriptionReminderSnoozed(
                snoozedUntilMillis = NOW,
                snoozedForExpiryMillis = EXPIRY,
                currentExpiryMillis = EXPIRY,
                nowMillis = NOW,
            ),
        )
    }

    private companion object {
        const val NOW = 2_000_000_000_000L
        const val TWO_HOURS_MS = 2L * 60L * 60L * 1000L
        const val TWELVE_HOURS_MS = 12L * 60L * 60L * 1000L
        const val TWO_DAYS_MS = 2L * 24L * 60L * 60L * 1000L
        const val EXPIRY = NOW + TWO_DAYS_MS
    }
}
