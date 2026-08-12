package com.tobevpn.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `rich state snapshot is kept beyond the former six hundred character limit`() {
        val safePrefix = "connection_state=CONNECTED "
        val safeBody = "probe=HEALTHY ".repeat(55)
        val decisiveTail = "last_downlink_age_ms=65432"

        val sanitized = DiagnosticLogPolicy.sanitizeMessage(
            safePrefix + safeBody + decisiveTail,
        )

        assertTrue(sanitized.length > 600)
        assertTrue(sanitized.contains(decisiveTail))
        assertTrue(sanitized.length <= 1_200)
    }

    @Test
    fun `rich state snapshot redacts secrets while retaining operational fields`() {
        val sanitized = DiagnosticLogPolicy.sanitizeMessage(
            "connection_state=CONNECTED endpoint=https://192.168.1.10/path " +
                "uuid=550e8400-e29b-41d4-a716-446655440000 " +
                "Authorization: Bearer secret_token_value " +
                "underlying_transport=CELLULAR downlink_kib=0",
        )

        assertTrue(sanitized.contains("connection_state=CONNECTED"))
        assertTrue(sanitized.contains("underlying_transport=CELLULAR"))
        assertTrue(sanitized.contains("downlink_kib=0"))
        assertFalse(sanitized.contains("192.168.1.10"))
        assertFalse(sanitized.contains("550e8400"))
        assertFalse(sanitized.contains("secret_token_value"))
    }

    @Test
    fun `large traffic counters and idle ages remain useful`() {
        val sanitized = DiagnosticLogPolicy.sanitizeMessage(
            "session_kib=1234567 uplink_kib=7654321 downlink_kib=9876543 " +
                "last_downlink_age_ms=12345678 duration_s=1234567 " +
                "link_up_kbps=1000000 link_down_kbps=2500000 " +
                "telegram_id=8907735498",
        )

        assertTrue(sanitized.contains("session_kib=1234567"))
        assertTrue(sanitized.contains("uplink_kib=7654321"))
        assertTrue(sanitized.contains("downlink_kib=9876543"))
        assertTrue(sanitized.contains("last_downlink_age_ms=12345678"))
        assertTrue(sanitized.contains("duration_s=1234567"))
        assertTrue(sanitized.contains("link_up_kbps=1000000"))
        assertTrue(sanitized.contains("link_down_kbps=2500000"))
        assertFalse(sanitized.contains("8907735498"))
        assertTrue(sanitized.contains("telegram_id=<redacted-id>"))
    }

    @Test
    fun `time in diagnostic message is not mistaken for ipv6`() {
        assertEquals(
            "handover_at=00:14:56 ipv6=<redacted-ip>",
            DiagnosticLogPolicy.sanitizeMessage(
                "handover_at=00:14:56 ipv6=2001:db8::1",
            ),
        )
    }

    @Test
    fun `header version is used when the journal has no update marker`() {
        val journal = sequenceOf(
            "# ToBeVPN diagnostic journal",
            "# App: 1.0.65 (66)",
            "# Locale: ru-RU",
            "2026-08-12 10:00:00.000 I/Vpn: connected",
        )

        assertEquals("1.0.65 (66)", DiagnosticLogPolicy.lastRecordedAppVersion(journal))
    }

    @Test
    fun `newest update marker wins over the header and earlier markers`() {
        val journal = sequenceOf(
            "# App: 1.0.64 (65)",
            "2026-08-12 10:00:00.000 I/Vpn: connected",
            "# App updated: 1.0.64 (65) -> 1.0.65 (66)",
            "2026-08-12 11:00:00.000 I/Vpn: connected",
            "# App updated: 1.0.65 (66) -> 1.0.66 (67)",
        )

        assertEquals("1.0.66 (67)", DiagnosticLogPolicy.lastRecordedAppVersion(journal))
    }

    @Test
    fun `journal without a version record yields null`() {
        val journal = sequenceOf(
            "# ToBeVPN diagnostic journal",
            "2026-08-12 10:00:00.000 I/Vpn: connected",
        )

        assertNull(DiagnosticLogPolicy.lastRecordedAppVersion(journal))
        assertNull(DiagnosticLogPolicy.lastRecordedAppVersion(emptySequence()))
    }

    @Test
    fun `a blank version record is ignored`() {
        assertNull(
            DiagnosticLogPolicy.lastRecordedAppVersion(sequenceOf("# App:   ")),
        )
    }

    @Test
    fun `tag sanitizer removes unsafe characters and enforces a fallback`() {
        assertEquals("Vpn_Manager", DiagnosticLogPolicy.sanitizeTag("Vpn Manager"))
        assertEquals("App", DiagnosticLogPolicy.sanitizeTag("   "))
    }
}
