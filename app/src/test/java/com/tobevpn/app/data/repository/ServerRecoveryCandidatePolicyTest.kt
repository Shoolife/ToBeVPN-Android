package com.tobevpn.app.data.repository

import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.ServerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRecoveryCandidatePolicyTest {

    @Test
    fun `failed endpoint is excluded even when panel exposes it under another id`() {
        val failed = server(id = "failed", address = "failed.example", sni = "sni.example")
        val duplicate = failed.copy(id = "duplicate", name = "Duplicate")
        val alternative = server(id = "other", address = "other.example", sni = "other.example")

        val eligible = ServerRecoveryCandidatePolicy.eligibleServers(
            servers = listOf(failed, duplicate, alternative),
            excludeServerId = failed.id,
            excludeEndpoint = failed,
        )

        assertEquals(listOf(alternative), eligible)
    }

    @Test
    fun `recovery does not silently restore sole excluded endpoint`() {
        val failed = server(id = "failed", address = "failed.example", sni = "sni.example")

        val eligible = ServerRecoveryCandidatePolicy.eligibleServers(
            servers = listOf(failed),
            excludeServerId = failed.id,
            excludeEndpoint = failed,
        )

        assertTrue(eligible.isEmpty())
    }

    @Test
    fun `normal automatic selection keeps all candidates`() {
        val first = server(id = "first", address = "first.example", sni = "first.example")
        val second = server(id = "second", address = "second.example", sni = "second.example")

        assertEquals(
            listOf(first, second),
            ServerRecoveryCandidatePolicy.eligibleServers(
                servers = listOf(first, second),
                excludeServerId = null,
                excludeEndpoint = null,
            ),
        )
    }

    @Test
    fun `failed bypass profile does not exclude sibling credentials on same host`() {
        val failed = server(
            id = "bs:failed-profile",
            address = "shared.example",
            sni = "front.example",
            source = ServerSource.BASE_STATION_BYPASS,
        )
        val sibling = failed.copy(
            id = "bs:sibling-profile",
            uuid = "22222222-2222-4222-8222-222222222222",
        )

        val eligible = ServerRecoveryCandidatePolicy.eligibleServers(
            servers = listOf(failed, sibling),
            excludeServerId = failed.id,
            excludeEndpoint = failed,
        )

        assertEquals(listOf(sibling), eligible)
        assertNotEquals(
            serverConnectionIdentityKey(failed),
            serverConnectionIdentityKey(sibling),
        )
        assertEquals(
            serverPingEndpointKey(failed),
            serverPingEndpointKey(sibling),
        )
    }

    @Test
    fun `automatic recovery never cycles back to an earlier failed profile`() {
        val first = server(
            id = "bs:first",
            address = "first.example",
            sni = "front.example",
            source = ServerSource.BASE_STATION_BYPASS,
        )
        val second = first.copy(
            id = "bs:second",
            uuid = "22222222-2222-4222-8222-222222222222",
        )
        val third = server(
            id = "bs:third",
            address = "third.example",
            sni = "other.example",
            source = ServerSource.BASE_STATION_BYPASS,
        )

        val eligible = ServerRecoveryCandidatePolicy.eligibleServers(
            servers = listOf(first, second, third),
            excludeServerId = null,
            excludeEndpoint = null,
            excludedServers = listOf(first, second),
        )

        assertEquals(listOf(third), eligible)
    }

    @Test
    fun `automatic recovery prefers a different endpoint over sibling credentials`() {
        val failed = server(
            id = "bs:failed",
            address = "shared.example",
            sni = "front.example",
            source = ServerSource.BASE_STATION_BYPASS,
        )
        val sibling = failed.copy(
            id = "bs:sibling",
            uuid = "22222222-2222-4222-8222-222222222222",
        )
        val otherEndpoint = server(
            id = "bs:other",
            address = "other.example",
            sni = "other.example",
            source = ServerSource.BASE_STATION_BYPASS,
        )

        val preferred = ServerRecoveryCandidatePolicy.preferUntriedEndpoints(
            servers = listOf(sibling, otherEndpoint),
            failedServers = listOf(failed),
        )

        assertEquals(listOf(otherEndpoint), preferred)
    }

    @Test
    fun `previously failed endpoint remains a second tier fallback`() {
        val failed = server(
            id = "bs:failed",
            address = "shared.example",
            sni = "front.example",
            source = ServerSource.BASE_STATION_BYPASS,
        )
        val sibling = failed.copy(
            id = "bs:sibling",
            uuid = "22222222-2222-4222-8222-222222222222",
        )
        val untried = server(
            id = "bs:untried",
            address = "other.example",
            sni = "other.example",
            source = ServerSource.BASE_STATION_BYPASS,
        )

        val tiers = ServerRecoveryCandidatePolicy.endpointPreferenceTiers(
            servers = listOf(sibling, untried),
            failedServers = listOf(failed),
        )

        assertEquals(listOf(untried), tiers.preferred)
        assertEquals(listOf(sibling), tiers.fallback)
    }

    @Test
    fun `automatic recovery keeps sibling credentials as last endpoint fallback`() {
        val failed = server(
            id = "bs:failed",
            address = "shared.example",
            sni = "front.example",
            source = ServerSource.BASE_STATION_BYPASS,
        )
        val sibling = failed.copy(
            id = "bs:sibling",
            uuid = "22222222-2222-4222-8222-222222222222",
        )

        assertEquals(
            listOf(sibling),
            ServerRecoveryCandidatePolicy.preferUntriedEndpoints(
                servers = listOf(sibling),
                failedServers = listOf(failed),
            ),
        )
    }

    @Test
    fun `a recently failed profile is deprioritised without banning its host`() {
        val failed = server("bs:failed", "shared.example", "sni", ServerSource.BASE_STATION_BYPASS)
        val sibling = failed.copy(id = "bs:sibling", uuid = "33333333-3333-4333-8333-333333333333")
        val other = server("bs:other", "other.example", "sni", ServerSource.BASE_STATION_BYPASS)

        val tiers = ServerRecoveryCandidatePolicy.endpointPreferenceTiers(
            servers = listOf(failed, sibling, other),
            failedServers = emptyList(),
            penalisedProfiles = listOf(failed),
        )

        assertEquals(listOf(sibling, other), tiers.preferred)
        assertEquals(listOf(failed), tiers.fallback)
    }

    @Test
    fun `a fully penalised pool still yields candidates`() {
        val first = server("bs:1", "a.example", "sni", ServerSource.BASE_STATION_BYPASS)
        val second = server("bs:2", "b.example", "sni", ServerSource.BASE_STATION_BYPASS)

        val tiers = ServerRecoveryCandidatePolicy.endpointPreferenceTiers(
            servers = listOf(first, second),
            failedServers = emptyList(),
            penalisedProfiles = listOf(first, second),
        )

        assertEquals(listOf(first, second), tiers.preferred)
        assertEquals(emptyList<Server>(), tiers.fallback)
    }

    private fun server(
        id: String,
        address: String,
        sni: String,
        source: ServerSource = ServerSource.STANDARD,
    ): Server = Server(
        id = id,
        name = id,
        address = address,
        port = 443,
        uuid = "11111111-1111-4111-8111-111111111111",
        sni = sni,
        source = source,
    )
}
