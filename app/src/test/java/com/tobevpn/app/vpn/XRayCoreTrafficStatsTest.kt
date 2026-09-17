package com.tobevpn.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class XRayCoreTrafficStatsTest {

    @Test
    fun `parses both proxy directions from one native snapshot`() {
        val result = parseXRayOutboundTrafficStats(
            raw = "proxy,uplink,120;proxy,downlink,340;direct,uplink,50;",
            tag = "proxy",
        )

        assertEquals(120L, result.uplinkBytes)
        assertEquals(340L, result.downlinkBytes)
    }

    @Test
    fun `ignores malformed unknown and non-positive records`() {
        val result = parseXRayOutboundTrafficStats(
            raw = "bad;proxy,side,10;proxy,uplink,-1;proxy,downlink,nope;other,uplink,90;",
            tag = "proxy",
        )

        assertEquals(XRayOutboundTrafficStats(), result)
    }

    @Test
    fun `sums duplicate counters and saturates overflow`() {
        val result = parseXRayOutboundTrafficStats(
            raw = "proxy,uplink,7;proxy,uplink,8;" +
                "proxy,downlink,${Long.MAX_VALUE};proxy,downlink,1;",
            tag = "proxy",
        )

        assertEquals(15L, result.uplinkBytes)
        assertEquals(Long.MAX_VALUE, result.downlinkBytes)
    }
}
