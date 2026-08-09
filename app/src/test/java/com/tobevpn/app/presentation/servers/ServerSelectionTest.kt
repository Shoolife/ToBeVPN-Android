package com.tobevpn.app.presentation.servers

import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.ServerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerSelectionTest {

    @Test
    fun `bypass and standard servers with the same name cannot share a selection key`() {
        val standard = server(id = "standard", source = ServerSource.STANDARD)
        val bypass = server(id = "bs:opaque", source = ServerSource.BASE_STATION_BYPASS)

        assertNotEquals(serverSelectionKey(standard), serverSelectionKey(bypass))
    }

    @Test
    fun `manual bypass selection never falls back to a standard server`() {
        val standard = server(id = "standard", source = ServerSource.STANDARD)

        val resolved = resolveSelectedServer(
            servers = listOf(standard),
            selectedId = "bs:missing",
            selectedKey = "bs:server",
            allowFallback = false,
        )

        assertNull(resolved)
    }

    @Test
    fun `bypass stable id is its opaque profile id`() {
        val bypass = server(id = "bs:opaque", source = ServerSource.BASE_STATION_BYPASS)

        assertEquals("bs:opaque", stableServerId(bypass))
    }

    @Test
    fun `bypass list keys stay unique for otherwise identical visible servers`() {
        val first = server(
            id = "bs:first-config-hash",
            source = ServerSource.BASE_STATION_BYPASS,
        ).copy(network = "tcp")
        val second = first.copy(
            id = "bs:second-config-hash",
            network = "xhttp",
        )

        assertNotEquals(serverListItemKey(first), serverListItemKey(second))
    }

    @Test
    fun `automatic bypass fallback cannot cross into standard tab`() {
        val standard = server(id = "standard", source = ServerSource.STANDARD)
        val bypass = server(id = "bypass", source = ServerSource.BASE_STATION_BYPASS)

        val resolved = resolveSelectedServer(
            servers = listOf(standard, bypass),
            selectedId = "missing",
            selectedKey = "bs:missing",
            allowFallback = true,
        )

        assertEquals(bypass, resolved)
    }

    @Test
    fun `measured bypass servers put reachable lowest ping first and failures last`() {
        val unavailable = server("unavailable", ServerSource.BASE_STATION_BYPASS).copy(ping = -1)
        val slow = server("slow", ServerSource.BASE_STATION_BYPASS).copy(ping = 81)
        val fast = server("fast", ServerSource.BASE_STATION_BYPASS).copy(ping = 24)

        val sorted = sortBaseStationBypassServersForDisplay(
            servers = listOf(unavailable, slow, fast),
            pingsMeasured = true,
        )

        assertEquals(listOf("fast", "slow", "unavailable"), sorted.map(Server::id))
    }

    @Test
    fun `unmeasured bypass servers retain profile order`() {
        val first = server("first", ServerSource.BASE_STATION_BYPASS).copy(ping = -1)
        val second = server("second", ServerSource.BASE_STATION_BYPASS).copy(ping = 20)

        val sorted = sortBaseStationBypassServersForDisplay(
            servers = listOf(first, second),
            pingsMeasured = false,
        )

        assertEquals(listOf("first", "second"), sorted.map(Server::id))
    }

    @Test
    fun `automatic bypass choice uses lowest measured ping`() {
        val slow = server("slow", ServerSource.BASE_STATION_BYPASS).copy(ping = 70)
        val fast = server("fast", ServerSource.BASE_STATION_BYPASS).copy(ping = 18)

        assertEquals(fast, bestBaseStationBypassServer(listOf(slow, fast)))
    }

    private fun server(id: String, source: ServerSource) = Server(
        id = id,
        name = "Server",
        address = "node.example",
        port = 443,
        uuid = "uuid-$id",
        source = source,
    )
}
