package com.tobevpn.app.presentation.servers

import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.ServerSource
import com.tobevpn.app.presentation.components.serverDisplayName
import java.util.Locale

fun stableServerId(server: Server): String = when (server.source) {
    ServerSource.STANDARD -> "${server.address}:${server.port}:${server.sni}"
    ServerSource.BASE_STATION_BYPASS -> server.id
}

fun serverSelectionKey(server: Server): String = buildString {
    if (server.source == ServerSource.BASE_STATION_BYPASS) append("bs:")
    append(
        serverDisplayName(server.name, server.country)
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT),
    )
}

/**
 * Automatic selection is scoped to the tab from which it was enabled. The
 * source is encoded in the persisted selection key so existing installations
 * don't need another preference migration.
 */
fun automaticSelectionSource(selectedKey: String?): ServerSource =
    if (selectedKey?.startsWith("bs:") == true) {
        ServerSource.BASE_STATION_BYPASS
    } else {
        ServerSource.STANDARD
    }

fun isSelectedServer(
    server: Server,
    selectedId: String?,
    selectedKey: String?,
): Boolean {
    val idMatches = selectedId != null && (
        server.id == selectedId ||
            stableServerId(server) == selectedId ||
            server.uuid == selectedId
        )
    val keyMatches = selectedKey != null && serverSelectionKey(server) == selectedKey
    return idMatches || keyMatches
}

fun resolveSelectedServer(
    servers: List<Server>,
    selectedId: String?,
    selectedKey: String?,
    allowFallback: Boolean = true,
): Server? {
    val availableServers = servers.filter { it.isAvailable }
    val fallbackServers = availableServers.filter {
        it.source == automaticSelectionSource(selectedKey)
    }
    if (selectedId == null && selectedKey == null) {
        return fallbackServers.firstOrNull().takeIf { allowFallback }
    }
    return availableServers.firstOrNull { isSelectedServer(it, selectedId, selectedKey) }
        ?: fallbackServers.firstOrNull().takeIf { allowFallback }
}

/**
 * Keep the profile order until the user explicitly measures latency. Once a
 * measurement exists, reachable entries are ordered by ping and failed TCP
 * probes are grouped at the bottom. Sorting is stable for equal values.
 */
fun sortBaseStationBypassServersForDisplay(
    servers: List<Server>,
    pingsMeasured: Boolean,
): List<Server> {
    if (!pingsMeasured) return servers
    return servers.withIndex()
        .sortedWith(
            compareBy<IndexedValue<Server>> { indexed ->
                when {
                    !indexed.value.isAvailable || indexed.value.ping < 0L -> 2
                    indexed.value.ping == 0L -> 1
                    else -> 0
                }
            }.thenBy { indexed ->
                indexed.value.ping.takeIf { it > 0L } ?: Long.MAX_VALUE
            }.thenBy { indexed -> indexed.index },
        )
        .map(IndexedValue<Server>::value)
}

/** Pick the lowest measured latency, retaining a usable profile fallback. */
fun bestBaseStationBypassServer(servers: List<Server>): Server? =
    servers.asSequence()
        .filter { it.source == ServerSource.BASE_STATION_BYPASS && it.isAvailable }
        .filter { it.ping > 0L }
        .minByOrNull(Server::ping)
        ?: servers.firstOrNull {
            it.source == ServerSource.BASE_STATION_BYPASS && it.isAvailable
        }

/**
 * Compose requires every simultaneously visible LazyColumn item to have a
 * unique key. Bypass profiles can legitimately contain several credentials
 * for the same host/name whose transport-only fields differ, so their opaque
 * configuration hash is the only safe UI key.
 */
internal fun serverListItemKey(server: Server): String = when (server.source) {
    ServerSource.BASE_STATION_BYPASS -> server.id
    ServerSource.STANDARD -> listOf(
        serverSelectionKey(server),
        server.address,
        server.port.toString(),
        server.uuid,
        server.sni,
        server.publicKey,
        server.shortId,
    ).joinToString("|")
}
