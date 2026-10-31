package com.tobevpn.app.vpn

import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.ServerProtocol
import java.util.Locale

/**
 * Parses hysteria2:// (and the short hy2://) links into a [Server] that the
 * bundled Xray connects through its "hysteria" outbound.
 *
 * Format: hysteria2://auth@host[:port][/]?sni=&obfs=salamander&obfs-password=
 * &pinSHA256=&mport=#name. Like [VlessUrlParser] it splits by hand instead of
 * java.net.URI, so emoji and spaces in the name do not drop the server.
 *
 * fp= is not used: uTLS fingerprints shape TLS over TCP, not QUIC, and a
 * real 3x-ui server connected the same with and without it.
 *
 * insecure=1 cannot be honoured: this Xray removed allowInsecure, and a
 * self-signed server has to publish pinSHA256 instead.
 */
object Hysteria2UrlParser {

    private val SCHEMES = listOf("hysteria2://", "hy2://")
    private const val DEFAULT_PORT = 443
    private val PORT_LIST = Regex("""\d+(?:-\d+)?(?:,\d+(?:-\d+)?)*""")

    fun parse(url: String): Server? {
        val scheme = SCHEMES.firstOrNull { url.startsWith(it, ignoreCase = true) } ?: return null
        return try {
            val withoutScheme = url.substring(scheme.length)
            val fragmentSplit = withoutScheme.split("#", limit = 2)
            val querySplit = fragmentSplit[0].split("?", limit = 2)
            val authority = querySplit[0].trimEnd('/')

            val atIndex = authority.lastIndexOf('@')
            if (atIndex <= 0) return null
            // The whole userinfo is the auth string, "user:pass" included.
            val auth = VlessUrlParser.percentDecode(authority.substring(0, atIndex))
            val (address, port, portSpec) = parseEndpoint(authority.substring(atIndex + 1))
                ?: return null
            if (auth.isBlank() || address.isBlank()) return null

            val params = VlessUrlParser.parseQueryParams(querySplit.getOrNull(1).orEmpty())
            val obfs = params["obfs"]?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val obfsPassword = params["obfs-password"].orEmpty()
                .takeIf { obfs.isEmpty() || obfs == "salamander" }
                .orEmpty()
            val name = fragmentSplit.getOrNull(1)
                ?.let(VlessUrlParser::percentDecode)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: address

            Server(
                id = auth,
                name = name,
                address = address,
                port = port,
                uuid = auth,
                security = "tls",
                sni = params["sni"]?.trim().orEmpty(),
                network = "hysteria",
                alpn = params["alpn"]?.trim()?.ifBlank { null } ?: "h3",
                protocol = ServerProtocol.HYSTERIA2,
                obfsPassword = obfsPassword,
                pinSha256 = normalizePin(params["pinSHA256"] ?: params["pinsha256"]),
                portHopping = params["mport"]?.trim()?.takeIf { PORT_LIST.matches(it) }
                    ?: portSpec.orEmpty(),
                echConfigList = params["ech"]?.trim().orEmpty(),
                finalMask = VlessUrlParser.normalizeFinalMask(params["fm"]),
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * host[:port] where the port may be a hopping spec ("443,5000-6000").
     * Returns the first port to dial and the spec when it lists more.
     */
    private fun parseEndpoint(hostPort: String): Triple<String, Int, String?>? {
        val bracketEnd = hostPort.indexOf(']')
        val colon = hostPort.lastIndexOf(':')
        val portPart = if (colon > bracketEnd) hostPort.substring(colon + 1) else ""
        if (portPart.isNotEmpty() && (',' in portPart || '-' in portPart)) {
            if (!PORT_LIST.matches(portPart)) return null
            val first = portPart.split(',', '-').first().toIntOrNull() ?: return null
            val host = hostPort.substring(0, colon).removePrefix("[").removeSuffix("]")
            return Triple(host, if (first in 1..65535) first else DEFAULT_PORT, portPart)
        }
        val (host, port) = VlessUrlParser.parseHostPort(hostPort) ?: return null
        return Triple(host, port, null)
    }

    // Hex SHA-256 with or without colons; anything else is dropped rather
    // than handed to Xray, which rejects the whole config on a bad pin.
    private fun normalizePin(raw: String?): String {
        val hex = raw?.replace(":", "")?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }.orEmpty()
    }
}
