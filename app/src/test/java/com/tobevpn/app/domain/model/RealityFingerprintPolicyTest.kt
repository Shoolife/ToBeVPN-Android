package com.tobevpn.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealityFingerprintPolicyTest {

    @Test
    fun `chrome profile gets one firefox fallback without duplicate chrome`() {
        val server = server(fingerprint = "Chrome")

        assertEquals(
            listOf("chrome", "firefox"),
            RealityFingerprintPolicy.candidates(server),
        )
        assertEquals(
            "firefox",
            RealityFingerprintPolicy.nextCandidate(server, setOf("CHROME")),
        )
        assertEquals(
            "chrome",
            RealityFingerprintPolicy.fingerprintForConfig(server, null),
        )
        assertNull(
            RealityFingerprintPolicy.nextCandidate(server, setOf("chrome", "firefox")),
        )
    }

    @Test
    fun `firefox declaration stays first and falls back to chrome`() {
        assertEquals(
            listOf("firefox", "chrome"),
            RealityFingerprintPolicy.candidates(server(fingerprint = "firefox")),
        )
    }

    @Test
    fun `custom compatible declaration remains first before firefox fallback`() {
        assertEquals(
            listOf("safari", "firefox"),
            RealityFingerprintPolicy.candidates(server(fingerprint = "safari")),
        )
    }

    @Test
    fun `legacy tls12 declaration starts with repaired chrome then firefox`() {
        assertEquals(
            listOf("chrome", "firefox"),
            RealityFingerprintPolicy.candidates(server(fingerprint = "android")),
        )
    }

    @Test
    fun `non reality profiles do not participate and ignore an override`() {
        val tls = server(fingerprint = "safari").copy(security = "tls")

        assertEquals(emptyList<String>(), RealityFingerprintPolicy.candidates(tls))
        assertNull(RealityFingerprintPolicy.primaryCandidate(tls))
        assertEquals(
            "safari",
            RealityFingerprintPolicy.fingerprintForConfig(tls, "firefox"),
        )
    }

    @Test
    fun `blank override cannot produce invalid xray fingerprint`() {
        val server = server(fingerprint = "chrome")

        assertEquals(
            "chrome",
            RealityFingerprintPolicy.fingerprintForConfig(server, "   "),
        )
        assertEquals(
            "firefox",
            RealityFingerprintPolicy.fingerprintForConfig(server, " firefox "),
        )
    }

    private fun server(fingerprint: String): Server = Server(
        id = "server-id",
        name = "Node",
        address = "node.example",
        port = 443,
        uuid = "550e8400-e29b-41d4-a716-446655440000",
        security = "reality",
        sni = "front.example",
        fingerprint = fingerprint,
        publicKey = "public-key",
        shortId = "abcd",
    )
}
