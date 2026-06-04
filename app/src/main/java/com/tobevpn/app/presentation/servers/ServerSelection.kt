package com.tobevpn.app.presentation.servers

import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.presentation.components.serverDisplayName
import java.util.Locale

fun stableServerId(server: Server): String =
    "${server.address}:${server.port}:${server.sni}"

fun serverSelectionKey(server: Server): String =
    serverDisplayName(server.name, server.country)
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)

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
    if (selectedId == null && selectedKey == null) {
        return availableServers.firstOrNull().takeIf { allowFallback }
    }
    return availableServers.firstOrNull { isSelectedServer(it, selectedId, selectedKey) }
        ?: availableServers.firstOrNull().takeIf { allowFallback }
}
