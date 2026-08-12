package com.tobevpn.app.util

import com.tobevpn.app.domain.model.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticServerDescriptorTest {

    @Test
    fun `descriptor is stable and excludes endpoint credentials`() {
        val server = testServer()

        val first = diagnosticServerDescriptor(server)
        val second = diagnosticServerDescriptor(server)

        assertEquals(first, second)
        assertTrue(first.contains("server_ref="))
        assertTrue(first.contains("country=NL"))
        assertTrue(first.contains("transport=tcp"))
        assertFalse(first.contains(server.address))
        assertFalse(first.contains(":${server.port}"))
        assertFalse(first.contains(" port="))
        assertFalse(first.contains(server.uuid))
        assertFalse(first.contains(server.sni))
        assertFalse(first.contains(server.publicKey))
    }

    @Test
    fun `descriptor reports the fingerprint handed to xray`() {
        val descriptor = diagnosticServerDescriptor(
            testServer().copy(security = "reality", fingerprint = "firefox"),
        )

        assertTrue(descriptor.contains("fingerprint=firefox"))
        assertFalse(descriptor.contains("declared_fingerprint="))
    }

    @Test
    fun `descriptor exposes both values when the fingerprint is repaired`() {
        val descriptor = diagnosticServerDescriptor(
            testServer().copy(security = "reality", fingerprint = "android"),
        )

        // The journal must show what Xray really used, and why it differs.
        assertTrue(descriptor.contains("fingerprint=chrome"))
        assertTrue(descriptor.contains("declared_fingerprint=android"))
    }

    @Test
    fun `hostile fingerprint text cannot break the journal format`() {
        val descriptor = diagnosticServerDescriptor(
            testServer().copy(security = "tls", fingerprint = "ch rome\nsafari"),
        )

        assertTrue(descriptor.contains("fingerprint=ch_rome_safari"))
        assertFalse(descriptor.contains("\n"))
    }

    @Test
    fun `different endpoints receive different references`() {
        val first = diagnosticServerDescriptor(testServer())
        val second = diagnosticServerDescriptor(
            testServer(address = "second.internal.example"),
        )

        assertNotEquals(first, second)
    }

    private fun testServer(
        address: String = "node.internal.example",
    ): Server = Server(
        id = "server-id",
        name = "Test server",
        address = address,
        port = 443,
        uuid = "550e8400-e29b-41d4-a716-446655440000",
        sni = "private.internal.example",
        publicKey = "private-public-key",
        country = "NL",
    )
}
