package com.tobevpn.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalNetworkSelectorTest {

    @Test
    fun `unvalidated candidate is not selected`() {
        val selector = PhysicalNetworkSelector<String>()

        val change = selector.update("wifi", validated = false, priority = 200)

        assertEquals(PhysicalNetworkSelector.ChangeType.UNCHANGED, change.type)
        assertNull(selector.selectedOrNull())
        assertFalse(selector.hasUsableNetwork())
    }

    @Test
    fun `first validated candidate establishes initial selection`() {
        val selector = PhysicalNetworkSelector<String>()

        val change = selector.update("cellular", validated = true, priority = 100)

        assertEquals(PhysicalNetworkSelector.ChangeType.INITIAL, change.type)
        assertEquals("cellular", selector.selectedOrNull())
    }

    @Test
    fun `higher priority wifi replaces cellular`() {
        val selector = PhysicalNetworkSelector<String>()
        selector.update("cellular", validated = true, priority = 100)

        val change = selector.update("wifi", validated = true, priority = 200)

        assertEquals(PhysicalNetworkSelector.ChangeType.HANDOVER, change.type)
        assertEquals("cellular", change.previous)
        assertEquals("wifi", change.current)
    }

    @Test
    fun `lower priority cellular does not disturb validated wifi`() {
        val selector = PhysicalNetworkSelector<String>()
        selector.update("wifi", validated = true, priority = 200)

        val change = selector.update("cellular", validated = true, priority = 100)

        assertEquals(PhysicalNetworkSelector.ChangeType.UNCHANGED, change.type)
        assertEquals("wifi", selector.selectedOrNull())
    }

    @Test
    fun `loss of wifi hands over to retained cellular candidate`() {
        val selector = PhysicalNetworkSelector<String>()
        selector.update("cellular", validated = true, priority = 100)
        selector.update("wifi", validated = true, priority = 200)

        val change = selector.onLost("wifi")

        assertEquals(PhysicalNetworkSelector.ChangeType.HANDOVER, change.type)
        assertEquals("cellular", selector.selectedOrNull())
    }

    @Test
    fun `selected network losing validation hands over to usable candidate`() {
        val selector = PhysicalNetworkSelector<String>()
        selector.update("cellular", validated = true, priority = 100)
        selector.update("wifi", validated = true, priority = 200)

        val change = selector.update("wifi", validated = false, priority = 200)

        assertEquals(PhysicalNetworkSelector.ChangeType.HANDOVER, change.type)
        assertEquals("cellular", selector.selectedOrNull())
    }

    @Test
    fun `loss of only validated network becomes unavailable`() {
        val selector = PhysicalNetworkSelector<String>()
        selector.update("wifi", validated = true, priority = 200)

        val change = selector.onLost("wifi")

        assertEquals(PhysicalNetworkSelector.ChangeType.UNAVAILABLE, change.type)
        assertNull(selector.selectedOrNull())
        assertFalse(selector.hasUsableNetwork())
    }

    @Test
    fun `equal priority candidate keeps current selection stable`() {
        val selector = PhysicalNetworkSelector<String>()
        selector.update("cellular-a", validated = true, priority = 100)

        val change = selector.update("cellular-b", validated = true, priority = 100)

        assertEquals(PhysicalNetworkSelector.ChangeType.UNCHANGED, change.type)
        assertTrue(selector.isSelected("cellular-a"))
    }

    @Test
    fun `loss of unselected candidate does not trigger handover`() {
        val selector = PhysicalNetworkSelector<String>()
        selector.update("wifi", validated = true, priority = 200)
        selector.update("cellular", validated = true, priority = 100)

        val change = selector.onLost("cellular")

        assertEquals(PhysicalNetworkSelector.ChangeType.UNCHANGED, change.type)
        assertEquals("wifi", selector.selectedOrNull())
    }

    @Test
    fun `network regaining validation becomes initial selection again`() {
        val selector = PhysicalNetworkSelector<String>()
        selector.update("wifi", validated = true, priority = 200)
        selector.update("wifi", validated = false, priority = 200)

        val change = selector.update("wifi", validated = true, priority = 200)

        assertEquals(PhysicalNetworkSelector.ChangeType.INITIAL, change.type)
        assertEquals("wifi", selector.selectedOrNull())
    }

    @Test
    fun `reset clears candidates and selection`() {
        val selector = PhysicalNetworkSelector<String>()
        selector.update("wifi", validated = true, priority = 200)

        selector.reset()
        val lostAfterReset = selector.onLost("wifi")

        assertNull(selector.selectedOrNull())
        assertFalse(selector.hasUsableNetwork())
        assertEquals(PhysicalNetworkSelector.ChangeType.UNCHANGED, lostAfterReset.type)
    }
}
