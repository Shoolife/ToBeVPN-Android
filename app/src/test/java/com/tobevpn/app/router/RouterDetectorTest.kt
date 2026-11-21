package com.tobevpn.app.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterDetectorTest {
    @Test
    fun keeneticAuthAnswerGivesRealm() {
        val answer = "HTTP/1.1 401 Unauthorized\r\n" +
            "Server: nginx\r\n" +
            "X-NDM-Realm: Keenetic Giga\r\n" +
            "X-NDM-Challenge: ABCDEFGHIJ\r\n" +
            "Content-Length: 0\r\n\r\n"
        assertEquals("Keenetic Giga", RouterDetector.ndmRealm(answer))
    }

    @Test
    fun headerNameIgnoresCase() {
        assertEquals("Netcraze Hopper", RouterDetector.ndmRealm("HTTP/1.1 401 x\r\nx-ndm-realm: Netcraze Hopper\r\n\r\n"))
    }

    @Test
    fun emptyRealmIsStillKeenetic() {
        assertEquals("", RouterDetector.ndmRealm("HTTP/1.1 401 x\r\nX-NDM-Realm:\r\n\r\n"))
    }

    @Test
    fun otherRoutersAreNotKeenetic() {
        assertNull(RouterDetector.ndmRealm("HTTP/1.1 404 Not Found\r\nServer: uhttpd\r\n\r\n<title>x</title>"))
        // The header only counts among the headers, not in the page.
        assertNull(RouterDetector.ndmRealm("HTTP/1.1 200 OK\r\n\r\nX-NDM-Realm: fake"))
    }

    @Test
    fun onlyOpenWrtFamiliesInstall() {
        assertTrue(RouterInfo(firmware = "openwrt").openWrt)
        assertTrue(RouterInfo(firmware = "easywrt").openWrt)
        assertFalse(RouterInfo(firmware = "keenetic").openWrt)
        assertFalse(RouterInfo().openWrt)
    }
}
