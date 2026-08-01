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
