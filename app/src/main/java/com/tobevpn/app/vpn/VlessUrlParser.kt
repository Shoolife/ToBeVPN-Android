package com.tobevpn.app.vpn

import com.tobevpn.app.domain.model.Server
import java.net.URLDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Locale

object VlessUrlParser {

    private const val MAX_FINAL_MASK_CHARS = 16_384


    private const val SCHEME = "vless://"
    private const val DEFAULT_PORT = 443

    /**
     * Parses a vless:// link into a [Server].
     *
     * Deliberately does NOT go through java.net.URI: real-world subscription
     * links routinely carry unencoded spaces/emoji in the fragment (server
     * remarks), which make the URI constructor throw and silently drop the
     * server. Splitting by hand accepts anything the regex in
     * SubscriptionPinger already extracted.
     */
    fun parse(url: String): Server? {
        if (!url.startsWith(SCHEME)) return null

        return try {
            val withoutScheme = url.removePrefix(SCHEME)
            val fragmentSplit = withoutScheme.split("#", limit = 2)
            val querySplit = fragmentSplit[0].split("?", limit = 2)
            val authority = querySplit[0]

            val atIndex = authority.lastIndexOf('@')
            if (atIndex <= 0) return null
            val uuid = percentDecode(authority.substring(0, atIndex))
            val (address, port) = parseHostPort(authority.substring(atIndex + 1)) ?: return null
            if (uuid.isBlank() || address.isBlank()) return null

            val name = fragmentSplit.getOrNull(1)
                ?.let(::percentDecode)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: address
            val params = parseQueryParams(querySplit.getOrNull(1) ?: "")

            Server(
                id = uuid,
                name = name,
                address = address,
                port = port,
                uuid = uuid,
                flow = params["flow"] ?: "",
                security = params["security"]
                    ?.trim()
                    ?.lowercase(Locale.ROOT)
                    ?.ifBlank { "none" }
                    ?: "none",
                sni = params["sni"] ?: "",
                fingerprint = params["fp"]
                    ?.trim()
                    ?.lowercase(Locale.ROOT)
                    ?.ifBlank { "chrome" }
                    ?: "chrome",
                publicKey = params["pbk"] ?: "",
                shortId = params["sid"] ?: "",
                network = normalizeNetwork(params["type"]),
                path = params["path"] ?: "",
                host = params["host"] ?: "",
                alpn = params["alpn"] ?: "",
                headerType = params["headerType"]
                    ?.trim()
                    ?.lowercase(Locale.ROOT)
                    ?: "",
                serviceName = params["serviceName"] ?: "",
                extra = params["extra"]
                    ?.takeUnless { it.trim() == "null" }
                    .orEmpty(),
                mode = params["mode"]
                    ?.trim()
                    ?.lowercase(Locale.ROOT)
                    ?: "",
                spx = params["spx"] ?: "",
                finalMask = normalizeFinalMask(params["fm"]),
                country = "",
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseHostPort(hostPort: String): Pair<String, Int>? {
        if (hostPort.isBlank()) return null
        // IPv6 literal: [2001:db8::1]:443
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end < 0) return null
            val host = hostPort.substring(1, end)
            val rest = hostPort.substring(end + 1)
            val port = when {
                rest.isEmpty() -> DEFAULT_PORT
                rest.startsWith(":") -> rest.drop(1).toIntOrNull() ?: return null
                else -> return null
            }
            return host to normalizePort(port)
        }
        val colon = hostPort.lastIndexOf(':')
        if (colon < 0) return hostPort to DEFAULT_PORT
        val port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
        return hostPort.substring(0, colon) to normalizePort(port)
    }

    private fun normalizePort(port: Int): Int =
        if (port in 1..65535) port else DEFAULT_PORT

    /**
     * Xray accepts both current transport names and a few historical aliases.
     * Keep one canonical representation in the database so filtering,
     * identity comparison and config generation cannot disagree about RAW,
     * WebSocket or SplitHTTP profiles supplied by a remote subscription.
     */
    private fun normalizeNetwork(rawNetwork: String?): String =
        when (rawNetwork?.trim()?.lowercase(Locale.ROOT).orEmpty()) {
            "", "raw", "tcp" -> "tcp"
            "websocket", "ws" -> "ws"
            "splithttp", "xhttp" -> "xhttp"
            else -> rawNetwork.orEmpty().trim().lowercase(Locale.ROOT)
        }

    /**
     * Percent-decoding without form semantics: URLDecoder turns '+' into a
     * space, which corrupts literal '+' characters in fragments and paths —
     * protect them before delegating.
     */
    /**
     * The fm= value (already percent-decoded) as compact JSON when it is a
     * JSON object, else "": Xray rejects the whole config on a malformed
     * finalmask, so a broken one is dropped instead of breaking the server.
     */
    internal fun normalizeFinalMask(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_FINAL_MASK_CHARS) return ""
        val element = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        return (element as? JsonObject)?.takeIf { it.isNotEmpty() }?.toString().orEmpty()
    }

    internal fun percentDecode(value: String): String {
        return try {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            value
        }
    }

    internal fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                percentDecode(parts[0]) to percentDecode(parts[1])
            } else null
        }.toMap()
    }
}
