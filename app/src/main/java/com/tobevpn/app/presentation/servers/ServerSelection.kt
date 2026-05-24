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
): Server? {
    if (selectedId == null && selectedKey == null) return servers.firstOrNull()
    return servers.firstOrNull { isSelectedServer(it, selectedId, selectedKey) }
}
