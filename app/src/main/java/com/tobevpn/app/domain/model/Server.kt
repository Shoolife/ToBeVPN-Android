package com.tobevpn.app.domain.model

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
    val mode: String = "",
    val spx: String = "",
    val country: String = "",
    val ping: Long = -1,
    val isOnline: Boolean = true,
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
}
