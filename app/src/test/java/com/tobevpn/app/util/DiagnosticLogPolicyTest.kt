package com.tobevpn.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DiagnosticLogPolicyTest {

    @Test
    fun `current date is encoded in a stable journal filename`() {
        assertEquals(
            "ToBeVPN-diagnostic-2026-07-31.log",
            DiagnosticLogPolicy.fileName(LocalDate.of(2026, 7, 31)),
        )
        assertTrue(
            DiagnosticLogPolicy.isDiagnosticLogFile(
                "ToBeVPN-diagnostic-2026-07-31.log",
            ),
        )
        assertFalse(DiagnosticLogPolicy.isDiagnosticLogFile("unrelated.txt"))
        assertFalse(
            DiagnosticLogPolicy.isDiagnosticLogFile(
                "ToBeVPN-diagnostic-not-a-date.log",
            ),
        )
        assertFalse(
            DiagnosticLogPolicy.isDiagnosticLogFile(
                "ToBeVPN-diagnostic-2026-07-31.log.backup",
            ),
        )
    }

    @Test
    fun `history retention selects only logs beyond the newest seven days`() {
        val names = (1..9).map { day ->
            DiagnosticLogPolicy.fileName(LocalDate.of(2026, 7, day))
        } + "unrelated.txt"

        val namesToDelete = DiagnosticLogPolicy.filesBeyondHistoryLimit(
            names = names,
            maxFiles = 7,
        )

        assertEquals(
            setOf(
                DiagnosticLogPolicy.fileName(LocalDate.of(2026, 7, 1)),
                DiagnosticLogPolicy.fileName(LocalDate.of(2026, 7, 2)),
            ),
            namesToDelete,
        )
        assertFalse("unrelated.txt" in namesToDelete)
    }

    @Test
    fun `message sanitizer removes credentials and user identifiers`() {
        val input = """
            request https://vpn.example/path?token=abc
            Authorization: Bearer secret_token_value
            user@example.com 123456789
            550e8400-e29b-41d4-a716-446655440000 192.168.10.4
        """.trimIndent()

        val sanitized = DiagnosticLogPolicy.sanitizeMessage(input)

        assertFalse(sanitized.contains("vpn.example"))
        assertFalse(sanitized.contains("secret_token_value"))
        assertFalse(sanitized.contains("user@example.com"))
        assertFalse(sanitized.contains("123456789"))
        assertFalse(sanitized.contains("550e8400"))
        assertFalse(sanitized.contains("192.168.10.4"))
        assertFalse(sanitized.contains('\n'))
    }

    @Test
    fun `message sanitizer keeps useful generic failure categories`() {
        assertEquals(
            "Subscription sync failed: HTTP_401",
            DiagnosticLogPolicy.sanitizeMessage(
                "Subscription sync failed: HTTP_401",
            ),
        )
        assertEquals(
            "VPN state changed: CONNECTED",
            DiagnosticLogPolicy.sanitizeMessage(
                "VPN state changed: CONNECTED",
            ),
        )
    }

    @Test
    fun `tag sanitizer removes unsafe characters and enforces a fallback`() {
        assertEquals("Vpn_Manager", DiagnosticLogPolicy.sanitizeTag("Vpn Manager"))
        assertEquals("App", DiagnosticLogPolicy.sanitizeTag("   "))
    }
}
