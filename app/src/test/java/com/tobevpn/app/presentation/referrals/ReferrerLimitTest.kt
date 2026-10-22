package com.tobevpn.app.presentation.referrals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferrerLimitTest {

    @Test
    fun readsDeltaSeconds() {
        assertEquals(3600L, parseRetryAfterSeconds("3600", 0L))
        assertEquals(42L, parseRetryAfterSeconds(" 42 ", 0L))
        assertEquals(0L, parseRetryAfterSeconds("-5", 0L))
    }

    @Test
    fun readsHttpDate() {
        // Thu, 01 Jan 1970 01:00:00 GMT is 3600 s after the epoch.
        assertEquals(3590L, parseRetryAfterSeconds("Thu, 01 Jan 1970 01:00:00 GMT", 10_000L))
    }

    @Test
    fun missingOrGarbageIsNull() {
        assertNull(parseRetryAfterSeconds(null, 0L))
        assertNull(parseRetryAfterSeconds("", 0L))
        assertNull(parseRetryAfterSeconds("soon", 0L))
    }

    @Test
    fun limitHoldsUntilTheWindowEnds() {
        val state = ReferralsUiState(referrerLimitedUntil = 10_000L)
        assertTrue(state.isReferrerLimited(9_999L))
        assertFalse(state.isReferrerLimited(10_000L))
        assertFalse(ReferralsUiState().isReferrerLimited(0L))
    }
}
