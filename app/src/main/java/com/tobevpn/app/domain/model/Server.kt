package com.tobevpn.app.domain.model

import java.util.Locale

data class Server(
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val uuid: String,
    val flow: String = "",
    val security: String = "reality",
    val sni: String = "",
    val fingerprint: String = "chrome",
    val publicKey: String = "",
    val shortId: String = "",
    val network: String = "tcp",
    val path: String = "",
    val host: String = "",
    val alpn: String = "",
    val headerType: String = "",
    val serviceName: String = "",
    val extra: String = "",
    val mode: String = "",
    val spx: String = "",
    val country: String = "",
    val ping: Long = -1,
    val isOnline: Boolean = true,
    val source: ServerSource = ServerSource.STANDARD,
) {
    /**
     * True for the placeholder "subscription expired" entry the panel
     * injects into expired users' subscriptions. It looks like a real
     * VLESS link (so the parser accepts it) but its uuid is the all-
     * zeros UUID and the address points nowhere — passing it to xray
     * triggers a native crash. Treat it as un-connectable so the UI
     * can either hide it or refuse to start a tunnel against it.
     */
    val isSentinel: Boolean
        get() = uuid == "00000000-0000-0000-0000-000000000000" ||
            address.isBlank() ||
            address == "127.0.0.1" ||
            address == "0.0.0.0" ||
            name.contains("ИСТЕКЛА", ignoreCase = true) ||
            name.contains("EXPIRED", ignoreCase = true) ||
            name.contains("истекла", ignoreCase = true)

    /**
     * REALITY always needs a TLS 1.3-capable ClientHello. These explicit uTLS
     * profiles only describe TLS 1.2 and are rejected by the bundled Xray
     * before the REALITY handshake can start.
     *
     * VpnConfig repairs such a fingerprint to Chrome for every source. Hiding
     * the server instead would be strictly worse: the declared camouflage
     * cannot be honoured either way, and the entry would simply disappear.
     */
    val isXrayCompatible: Boolean
        get() = !security.trim().equals("reality", ignoreCase = true) ||
            fingerprint.trim().lowercase(Locale.ROOT) !in TLS12_ONLY_REALITY_FINGERPRINTS

    /**
     * The uTLS fingerprint actually handed to Xray — the single source of
     * truth for both config generation and diagnostics, so a journal can never
     * disagree with what the tunnel really negotiated.
     */
    val effectiveFingerprint: String
        get() {
            val requested = fingerprint.trim().ifBlank { DEFAULT_FINGERPRINT }
            return if (isXrayCompatible) requested else DEFAULT_FINGERPRINT
        }

    /** True when [effectiveFingerprint] had to override the declared value. */
    val isFingerprintRepaired: Boolean
        get() = !isXrayCompatible

    /**
     * Panel online metadata can lag behind real VLESS/Reality reachability.
     * Only sentinel/expired placeholders are not connectable; probes and Xray
     * decide whether a normal server actually carries traffic.
     */
    val isAvailable: Boolean
        get() = !isSentinel

    /**
     * The server can be selected from the list. TCP ping is only a quality
     * signal; a failed probe does not prove the VLESS/Reality tunnel is broken.
     */
    val isSelectable: Boolean
        get() = isAvailable

    /**
     * Compares only fields that affect the XRay outbound. Country, online
     * metadata and ping can change without requiring a tunnel restart.
     */
    fun hasSameVpnConfig(other: Server): Boolean =
        address == other.address &&
            port == other.port &&
            uuid == other.uuid &&
            flow == other.flow &&
            security == other.security &&
            sni == other.sni &&
            fingerprint == other.fingerprint &&
            publicKey == other.publicKey &&
            shortId == other.shortId &&
            network == other.network &&
            path == other.path &&
            host == other.host &&
            alpn == other.alpn &&
            headerType == other.headerType &&
            serviceName == other.serviceName &&
            extra == other.extra &&
            mode == other.mode &&
            spx == other.spx

    private companion object {
        const val DEFAULT_FINGERPRINT = "chrome"
        val TLS12_ONLY_REALITY_FINGERPRINTS = setOf(
            "android",
            "helloandroid_11_okhttp",
            "hellochrome_58",
            "hellochrome_62",
            "hellofirefox_55",
            "hellofirefox_56",
            "helloios_11_1",
        )
    }
}

enum class ServerSource {
    STANDARD,
    BASE_STATION_BYPASS,
}
