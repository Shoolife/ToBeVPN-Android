package com.tobevpn.app.domain.model

/**
 * Manual uTLS fingerprint for the base-station bypass servers. Profiles carry
 * their own fingerprint, but a single fingerprint can be filtered by the
 * censor, so the user may replace it for every bypass server at once.
 * An empty value keeps each profile's own fingerprint.
 */
object FingerprintOverride {
    const val FROM_PROFILE = ""

    /** The values the bundled Xray accepts, in the order they are offered. */
    val VALUES = listOf(
        "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq",
        "random", "randomized",
    )

    /**
     * Fingerprints a current REALITY server accepts. It refuses a ClientHello
     * without the X25519MLKEM768 key share, which only these templates send:
     * checked against an Xray 26.10 REALITY server, ios/android/edge/360/qq
     * came back "received real certificate" (not recognised), and random
     * picks one of those old templates at times.
     */
    val REALITY_COMPATIBLE = setOf("chrome", "firefox", "safari", "randomized")

    fun isRealityCompatible(value: String): Boolean = value.isEmpty() || value in REALITY_COMPATIBLE

    fun normalize(value: String?): String =
        value?.trim()?.lowercase()?.takeIf { it in VALUES } ?: FROM_PROFILE

    /**
     * Applies [override] to [servers]. The profile value stays when it is
     * empty, and on REALITY servers when the override would get them
     * refused; TLS servers take any value.
     */
    fun apply(servers: List<Server>, override: String): List<Server> {
        if (override.isEmpty()) return servers
        val realityOk = isRealityCompatible(override)
        return servers.map { server ->
            val reality = server.security.trim().equals("reality", ignoreCase = true)
            if (reality && !realityOk) server else server.copy(fingerprint = override)
        }
    }
}
