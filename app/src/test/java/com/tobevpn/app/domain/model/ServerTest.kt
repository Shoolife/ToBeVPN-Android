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
    fun `legacy reality fingerprint stays selectable and is repaired for every source`() {
        val reality = server().copy(fingerprint = "Android")
        val explicitAndroid = reality.copy(fingerprint = "HelloAndroid_11_OkHttp")
        val tls = reality.copy(security = "tls")
        val publicBypass = reality.copy(source = ServerSource.BASE_STATION_BYPASS)

        // Flagged as needing repair...
        assertFalse(reality.isXrayCompatible)
        assertFalse(explicitAndroid.isXrayCompatible)
        // ...but never hidden: VpnConfig substitutes a TLS 1.3 fingerprint.
        assertTrue(reality.isAvailable)
        assertTrue(explicitAndroid.isAvailable)
        assertTrue(publicBypass.isAvailable)
        assertTrue(tls.isXrayCompatible)
        assertTrue(tls.isAvailable)
    }

    @Test
    fun `known tls12-only legacy fingerprints are incompatible with reality`() {
        val legacyFingerprints = listOf(
            "helloChrome_58",
            "helloChrome_62",
            "helloFirefox_55",
            "helloFirefox_56",
            "helloIOS_11_1",
        )

        legacyFingerprints.forEach { fingerprint ->
            assertFalse(server().copy(fingerprint = fingerprint).isXrayCompatible)
        }
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
        assertFalse(original.hasSameVpnConfig(original.copy(host = "cdn.example")))
        assertFalse(original.hasSameVpnConfig(original.copy(alpn = "http/1.1")))
        assertFalse(original.hasSameVpnConfig(original.copy(headerType = "http")))
        assertFalse(original.hasSameVpnConfig(original.copy(serviceName = "edge")))
        assertFalse(original.hasSameVpnConfig(original.copy(extra = "{\"key\":true}")))
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
