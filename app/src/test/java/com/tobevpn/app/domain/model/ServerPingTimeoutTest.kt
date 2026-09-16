package com.tobevpn.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerPingTimeoutTest {
    @Test
    fun `timeout is clamped to supported range`() {
        assertEquals(MIN_SERVER_PING_TIMEOUT_SECONDS, normalizeServerPingTimeoutSeconds(1))
        assertEquals(DEFAULT_SERVER_PING_TIMEOUT_SECONDS, normalizeServerPingTimeoutSeconds(7))
        assertEquals(MAX_SERVER_PING_TIMEOUT_SECONDS, normalizeServerPingTimeoutSeconds(30))
    }
}
