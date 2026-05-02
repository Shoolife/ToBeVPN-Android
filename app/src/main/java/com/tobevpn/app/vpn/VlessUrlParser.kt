package com.tobevpn.app.vpn

import com.tobevpn.app.domain.model.Server
import java.net.URI
import java.net.URLDecoder

object VlessUrlParser {

    fun parse(url: String): Server? {
        if (!url.startsWith("vless://")) return null

        return try {
            val uri = URI(url)
            val uuid = uri.userInfo ?: return null
            val address = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 443
            val name = URLDecoder.decode(uri.fragment ?: address, "UTF-8")
            val params = parseQueryParams(uri.rawQuery ?: "")

            Server(
                id = uuid,
                name = name,
                address = address,
                port = port,
                uuid = uuid,
                flow = params["flow"] ?: "",
                security = params["security"] ?: "none",
                sni = params["sni"] ?: "",
                fingerprint = params["fp"] ?: "chrome",
                publicKey = params["pbk"] ?: "",
                shortId = params["sid"] ?: "",
                network = params["type"] ?: "tcp",
                path = params["path"] ?: "",
                mode = params["mode"] ?: "",
                spx = params["spx"] ?: "",
                country = "",
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            } else null
        }.toMap()
    }
}
