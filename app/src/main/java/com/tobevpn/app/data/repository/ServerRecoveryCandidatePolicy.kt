package com.tobevpn.app.data.repository

import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.ServerSource

/**
 * Strictly removes a failed standard endpoint or one exact bypass profile.
 * Several bypass credentials may legitimately share the same TCP endpoint.
 */
internal object ServerRecoveryCandidatePolicy {
    data class EndpointPreferenceTiers(
        val preferred: List<Server>,
        val fallback: List<Server>,
    )

    fun eligibleServers(
        servers: List<Server>,
        excludeServerId: String?,
        excludeEndpoint: Server?,
        excludedServers: Collection<Server> = emptyList(),
    ): List<Server> {
        val excludedIds = buildSet {
            excludeServerId?.let(::add)
            excludedServers.mapTo(this, Server::id)
        }
        val excludedEndpointKeys = buildSet {
            excludeEndpoint?.let { add(serverConnectionIdentityKey(it)) }
            excludedServers.mapTo(this, ::serverConnectionIdentityKey)
        }
        return servers.filterNot { server ->
            server.id in excludedIds ||
                serverConnectionIdentityKey(server) in excludedEndpointKeys
        }
    }

    /**
     * Prefer a genuinely different network endpoint during recovery. Bypass
     * profiles sharing an endpoint can still be useful when no other endpoint
     * exists, so this is a preference rather than another hard exclusion.
     */
    fun preferUntriedEndpoints(
        servers: List<Server>,
        failedServers: Collection<Server>,
        penalisedProfiles: Collection<Server> = emptyList(),
    ): List<Server> =
        endpointPreferenceTiers(servers, failedServers, penalisedProfiles).preferred

    /**
     * Separates the soft preference from its fallback. The caller first
     * probes/ranks fresh candidates, then falls back to previously failed ones
     * if every preferred candidate is currently unreachable.
     *
     * Two independent signals are deprioritised: endpoints that failed in the
     * current recovery episode (host-scoped) and individual profiles that
     * recently failed end-to-end validation (identity-scoped). Both are
     * preferences — a fully deprioritised pool still yields candidates.
     */
    fun endpointPreferenceTiers(
        servers: List<Server>,
        failedServers: Collection<Server>,
        penalisedProfiles: Collection<Server> = emptyList(),
    ): EndpointPreferenceTiers {
        if (servers.isEmpty()) {
            return EndpointPreferenceTiers(preferred = servers, fallback = emptyList())
        }
        val failedEndpointKeys = failedServers.mapTo(mutableSetOf(), ::serverPingEndpointKey)
        val penalisedKeys = penalisedProfiles.mapTo(mutableSetOf(), ::serverConnectionIdentityKey)
        if (failedEndpointKeys.isEmpty() && penalisedKeys.isEmpty()) {
            return EndpointPreferenceTiers(preferred = servers, fallback = emptyList())
        }
        val deprioritised = { server: Server ->
            serverPingEndpointKey(server) in failedEndpointKeys ||
                serverConnectionIdentityKey(server) in penalisedKeys
        }
        val preferred = servers.filterNot(deprioritised)
        if (preferred.isEmpty()) {
            return EndpointPreferenceTiers(preferred = servers, fallback = emptyList())
        }
        return EndpointPreferenceTiers(
            preferred = preferred,
            fallback = servers.filter(deprioritised),
        )
    }
}

/**
 * STANDARD entries that point to the same endpoint are interchangeable panel
 * aliases. Bypass entries are not: profiles on one host can carry different
 * credentials and transport settings, so their full-config hash is required.
 */
internal fun serverConnectionIdentityKey(server: Server): String =
    when (server.source) {
        ServerSource.STANDARD -> serverPingEndpointKey(server)
        ServerSource.BASE_STATION_BYPASS -> server.id
    }

/** TCP reachability is host-scoped and may safely be shared between profiles. */
internal fun serverPingEndpointKey(server: Server): String =
    "${server.address}:${server.port}:${server.sni}"
