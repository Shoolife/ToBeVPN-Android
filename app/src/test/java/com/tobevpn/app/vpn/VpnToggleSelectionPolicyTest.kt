package com.tobevpn.app.vpn

import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.ServerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnToggleSelectionPolicyTest {

    @Test
    fun `cold start keeps an existing automatic bypass profile without reselection`() {
        assertTrue(
            shouldKeepAutomaticSelectionOnRefresh(
                source = ServerSource.BASE_STATION_BYPASS,
                forceSelection = true,
                selectedStillAvailable = true,
            ),
        )
    }

    @Test
    fun `missing automatic bypass profile requires a new selection`() {
        assertFalse(
            shouldKeepAutomaticSelectionOnRefresh(
                source = ServerSource.BASE_STATION_BYPASS,
                forceSelection = true,
                selectedStillAvailable = false,
            ),
        )
    }

    @Test
    fun `forced standard refresh still performs standard reselection`() {
        assertFalse(
            shouldKeepAutomaticSelectionOnRefresh(
                source = ServerSource.STANDARD,
                forceSelection = true,
                selectedStillAvailable = true,
            ),
        )
    }

    @Test
    fun `first automatic selection prefers a panel-online server without probing`() {
        val offline = server(name = "offline", address = "one.example", isOnline = false)
        val online = server(name = "online", address = "two.example", isOnline = true)

        assertEquals(online, immediateAutomaticServer(listOf(offline, online)))
    }

    @Test
    fun `first automatic selection ignores an expired sentinel`() {
        val sentinel = server(
            name = "SUBSCRIPTION EXPIRED",
            address = "127.0.0.1",
            isOnline = true,
            uuid = "00000000-0000-0000-0000-000000000000",
        )

        assertNull(immediateAutomaticServer(listOf(sentinel)))
    }

    private fun server(
        name: String,
        address: String,
        isOnline: Boolean,
        uuid: String = "11111111-1111-1111-1111-111111111111",
    ) = Server(
        id = address,
        name = name,
        address = address,
        port = 443,
        uuid = uuid,
        isOnline = isOnline,
    )
}
