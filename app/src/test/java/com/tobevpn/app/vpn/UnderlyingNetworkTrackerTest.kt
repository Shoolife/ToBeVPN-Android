package com.tobevpn.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnderlyingNetworkTrackerTest {

    @Test
    fun `first available network establishes baseline without handover`() {
        val tracker = UnderlyingNetworkTracker<String>()

        assertEquals(
            UnderlyingNetworkTracker.Availability.INITIAL,
            tracker.onAvailable("cellular"),
        )
    }

    @Test
    fun `duplicate callback for current network is ignored`() {
        val tracker = UnderlyingNetworkTracker<String>()
        tracker.onAvailable("cellular")

        assertEquals(
            UnderlyingNetworkTracker.Availability.UNCHANGED,
            tracker.onAvailable("cellular"),
        )
    }

    @Test
    fun `different network after baseline is a handover`() {
        val tracker = UnderlyingNetworkTracker<String>()
        tracker.onAvailable("cellular")

        assertEquals(
            UnderlyingNetworkTracker.Availability.HANDOVER,
            tracker.onAvailable("wifi"),
        )
    }

    @Test
    fun `loss of unrelated network does not affect tracked upstream`() {
        val tracker = UnderlyingNetworkTracker<String>()
        tracker.onAvailable("wifi")

        assertFalse(tracker.onLost("cellular"))
        assertTrue(tracker.isCurrent("wifi"))
        assertEquals(
            UnderlyingNetworkTracker.Availability.UNCHANGED,
            tracker.onAvailable("wifi"),
        )
    }

    @Test
    fun `availability after tracked network loss triggers recovery`() {
        val tracker = UnderlyingNetworkTracker<String>()
        tracker.onAvailable("cellular")
        assertTrue(tracker.onLost("cellular"))
        assertFalse(tracker.isAvailable("cellular"))

        assertEquals(
            UnderlyingNetworkTracker.Availability.HANDOVER,
            tracker.onAvailable("cellular"),
        )
        assertTrue(tracker.isAvailable("cellular"))
    }

    @Test
    fun `reset makes the next callback a fresh baseline`() {
        val tracker = UnderlyingNetworkTracker<String>()
        tracker.onAvailable("cellular")
        tracker.onAvailable("wifi")

        tracker.reset()

        assertEquals(
            UnderlyingNetworkTracker.Availability.INITIAL,
            tracker.onAvailable("wifi"),
        )
    }
}
