package com.tobevpn.app.vpn

import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.ServerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentTunnelFailureRegistryTest {

    private fun bypass(id: String, address: String = "node.example") = Server(
        id = id,
        name = "Bypass",
        address = address,
        port = 443,
        uuid = "uuid-$id",
        source = ServerSource.BASE_STATION_BYPASS,
    )

    @Test
    fun `failed profile stays penalised for the whole window`() {
        val registry = RecentTunnelFailureRegistry(penaltyMs = 10_000L)
        val profile = bypass("bs:aaa")

        registry.record(profile, nowMs = 1_000L)

        assertEquals(listOf(profile), registry.penalisedServers(nowMs = 10_999L))
        assertTrue(registry.penalisedServers(nowMs = 11_000L).isEmpty())
    }

    @Test
    fun `sibling profiles on one host are penalised independently`() {
        val registry = RecentTunnelFailureRegistry(penaltyMs = 10_000L)
        val failed = bypass("bs:aaa", address = "shared.example")
        val sibling = bypass("bs:bbb", address = "shared.example")

        registry.record(failed, nowMs = 0L)

        val penalised = registry.penalisedServers(nowMs = 1_000L)
        assertEquals(listOf(failed), penalised)
        assertTrue(penalised.none { it.id == sibling.id })
    }

    @Test
    fun `a confirmed healthy tunnel clears its own penalty`() {
        val registry = RecentTunnelFailureRegistry(penaltyMs = 10_000L)
        val profile = bypass("bs:aaa")
        registry.record(profile, nowMs = 0L)

        registry.forget(profile)

        assertTrue(registry.penalisedServers(nowMs = 1_000L).isEmpty())
    }

    @Test
    fun `backward clock cannot pin a profile forever`() {
        val registry = RecentTunnelFailureRegistry(penaltyMs = 10_000L)
        registry.record(bypass("bs:aaa"), nowMs = 50_000L)

        assertTrue(registry.penalisedServers(nowMs = 10L).isEmpty())
    }

    @Test
    fun `registry keeps only the newest entries`() {
        val registry = RecentTunnelFailureRegistry(penaltyMs = 60_000L, maxEntries = 2)

        registry.record(bypass("bs:1"), nowMs = 1L)
        registry.record(bypass("bs:2"), nowMs = 2L)
        registry.record(bypass("bs:3"), nowMs = 3L)

        val ids = registry.penalisedServers(nowMs = 4L).map(Server::id)
        assertEquals(listOf("bs:2", "bs:3"), ids)
    }
}
