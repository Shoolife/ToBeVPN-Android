package com.tobevpn.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VlessUrlParserTest {

    @Test
    fun `parses xhttp reality parameters without losing encoded values`() {
        val server = VlessUrlParser.parse(
            "vless://550e8400-e29b-41d4-a716-446655440000@node.example:8443" +
                "?type=xhttp&security=reality&sni=front.example&fp=chrome" +
                "&pbk=public-key&sid=abcd&path=%2Fapi%2Fv1&mode=stream-up" +
                "&spx=%2Fprobe%2Bplus#Test%20server%20%F0%9F%87%B3%F0%9F%87%B1",
        )

        requireNotNull(server)
        assertEquals("node.example", server.address)
        assertEquals(8443, server.port)
        assertEquals("xhttp", server.network)
        assertEquals("reality", server.security)
        assertEquals("front.example", server.sni)
        assertEquals("public-key", server.publicKey)
        assertEquals("abcd", server.shortId)
        assertEquals("/api/v1", server.path)
        assertEquals("stream-up", server.mode)
        assertEquals("/probe+plus", server.spx)
        assertEquals("Test server 🇳🇱", server.name)
    }

    @Test
    fun `parses bracketed ipv6 endpoint and websocket tls`() {
        val server = VlessUrlParser.parse(
            "vless://user-id@[2001:db8::7]:443" +
                "?type=ws&security=tls&sni=edge.example&path=%2Fws#IPv6%20WS",
        )

        requireNotNull(server)
        assertEquals("2001:db8::7", server.address)
        assertEquals(443, server.port)
        assertEquals("ws", server.network)
        assertEquals("tls", server.security)
        assertEquals("/ws", server.path)
        assertEquals("IPv6 WS", server.name)
    }

    @Test
    fun `keeps literal plus and accepts unencoded unicode remark`() {
        val server = VlessUrlParser.parse(
            "vless://user+id@node.example?type=tcp#Россия + тест",
        )

        requireNotNull(server)
        assertEquals("user+id", server.uuid)
        assertEquals("Россия + тест", server.name)
        assertEquals(443, server.port)
    }

    @Test
    fun `out of range port falls back to standard vless port`() {
        val server = VlessUrlParser.parse("vless://user@node.example:70000#Node")

        requireNotNull(server)
        assertEquals(443, server.port)
    }

    @Test
    fun `rejects malformed authority and ipv6 literal`() {
        assertNull(VlessUrlParser.parse("https://user@node.example:443"))
        assertNull(VlessUrlParser.parse("vless://node.example:443"))
        assertNull(VlessUrlParser.parse("vless://user@[2001:db8::7:443"))
        assertNull(VlessUrlParser.parse("vless://@node.example:443"))
    }
}
