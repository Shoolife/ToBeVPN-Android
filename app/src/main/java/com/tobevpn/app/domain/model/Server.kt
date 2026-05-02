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
)
