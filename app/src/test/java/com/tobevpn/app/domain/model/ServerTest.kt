package com.tobevpn.app.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerTest {

    @Test
    fun `expired placeholder cannot be selected or connected`() {
        val server = server(
            address = "127.0.0.1",
            uuid = "00000000-0000-0000-0000-000000000000",
            name = "SUBSCRIPTION EXPIRED",
        )

        assertTrue(server.isSentinel)
        assertFalse(server.isAvailable)
        assertFalse(server.isSelectable)
    }

    @Test
    fun `panel offline metadata does not make a real endpoint unselectable`() {
        val server = server(isOnline = false)

        assertFalse(server.isSentinel)
        assertTrue(server.isAvailable)
        assertTrue(server.isSelectable)
    }

    @Test
    fun `only xray relevant fields require tunnel restart`() {
        val original = server()

        assertTrue(
            original.hasSameVpnConfig(
                original.copy(name = "Renamed", country = "DE", ping = 900, isOnline = false),
            ),
        )
        assertFalse(original.hasSameVpnConfig(original.copy(path = "/new-path")))
        assertFalse(original.hasSameVpnConfig(original.copy(publicKey = "new-key")))
        assertFalse(original.hasSameVpnConfig(original.copy(network = "ws")))
    }

    private fun server(
        address: String = "node.example",
        uuid: String = "550e8400-e29b-41d4-a716-446655440000",
        name: String = "Node",
        isOnline: Boolean = true,
    ) = Server(
        id = "server-id",
        name = name,
        address = address,
        port = 443,
        uuid = uuid,
        security = "reality",
        sni = "front.example",
        publicKey = "public-key",
        shortId = "abcd",
        network = "xhttp",
        path = "/api",
        mode = "auto",
        country = "NL",
        isOnline = isOnline,
    )
}
