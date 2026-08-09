package com.tobevpn.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SafeDiagnosticsTest {

    @Test
    fun `failure summary keeps safe application frames without exception message`() {
        val error = IOException("https://secret.example/token-value").apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "com.tobevpn.app.vpn.VpnConnectionManager",
                    "probeTunnelOnce",
                    "VpnConnectionManager.kt",
                    1_925,
                ),
                StackTraceElement(
                    "okhttp3.RealCall",
                    "execute",
                    "RealCall.kt",
                    150,
                ),
            )
        }

        val summary = SafeDiagnostics.failureSummary(error)

        assertTrue(summary.contains("category=IO"))
        assertTrue(summary.contains("VpnConnectionManager.probeTunnelOnce:1925"))
        assertFalse(summary.contains("secret.example"))
        assertFalse(summary.contains("token-value"))
    }
}
