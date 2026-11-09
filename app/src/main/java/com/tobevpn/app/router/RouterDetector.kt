package com.tobevpn.app.router

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton

/** The home network the phone is on: its Wi-Fi (or Ethernet) and router. */
data class HomeLan(
    val network: Network,
    /** Default IPv4 gateway, e.g. "192.168.1.1". */
    val gateway: String,
    /** This phone's IPv4 address in it. */
    val localIp: String?,
)

/** The router of the current network, from its web login page. */
data class RouterInfo(
    val gateway: String? = null,
    /** Model from the login page title, e.g. "SNR-CPE-AX2". */
    val model: String? = null,
    /** "easywrt" (SNR's OpenWrt), "openwrt", or null when not recognised. */
    val firmware: String? = null,
)

/**
 * Finds the router of the Wi-Fi network as the desktop app does: the default
 * gateway, and the model from the login page, which needs no password. Only
 * two plain GET requests reach the router, through the Wi-Fi network itself
 * (not the phone's VPN).
 */
@Singleton
class RouterDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val connectivity: ConnectivityManager
        get() = context.getSystemService(ConnectivityManager::class.java)

    @Suppress("DEPRECATION")
    fun homeLan(): HomeLan? {
        val manager = connectivity
        return manager.allNetworks.firstNotNullOfOrNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
            val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!local || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return@firstNotNullOfOrNull null
            }
            val properties = manager.getLinkProperties(network) ?: return@firstNotNullOfOrNull null
            val gateway = properties.routes
                .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway?.hostAddress ?: return@firstNotNullOfOrNull null
            val localIp = properties.linkAddresses
                .map { it.address }
                .firstOrNull { it is Inet4Address }
                ?.hostAddress
            HomeLan(network, gateway, localIp)
        }
    }

    suspend fun detect(): RouterInfo = withContext(Dispatchers.IO) {
        val lan = homeLan() ?: return@withContext RouterInfo()
        val info = RouterInfo(gateway = lan.gateway)
        val root = httpGet(lan, "/") ?: return@withContext info
        // LuCI's index only redirects (meta refresh) to /cgi-bin/luci.
        val page = if (root.contains("/cgi-bin/luci")) httpGet(lan, "/cgi-bin/luci") ?: root else root
        val (model, firmware) = identify(page)
        info.copy(model = model, firmware = firmware)
    }

    private fun httpGet(lan: HomeLan, path: String): String? = runCatching {
        lan.network.socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(lan.gateway, 80), HTTP_TIMEOUT_MS)
            socket.soTimeout = HTTP_TIMEOUT_MS
            socket.getOutputStream().write(
                "GET $path HTTP/1.0\r\nHost: ${lan.gateway}\r\nConnection: close\r\n\r\n".toByteArray(),
            )
            val body = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            val input = socket.getInputStream()
            while (body.size() < MAX_PAGE_BYTES) {
                val read = runCatching { input.read(chunk) }.getOrDefault(-1)
                if (read <= 0) break
                body.write(chunk, 0, read)
            }
            body.toString(Charsets.UTF_8.name()).takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    companion object {
        private const val HTTP_TIMEOUT_MS = 3_000
        private const val MAX_PAGE_BYTES = 64 * 1024

        /** Model and firmware family from a router's login page. */
        fun identify(page: String): Pair<String?, String?> {
            val lower = page.lowercase()
            val firmware = when {
                "luci-static/snr" in lower || "easywrt" in lower -> "easywrt"
                "luci" in lower || "openwrt" in lower -> "openwrt"
                else -> null
            }
            val title = pageTitle(page)?.takeIf {
                val generic = it.lowercase()
                generic.isNotEmpty() && generic != "luci" && generic != "openwrt" && generic.length <= 64
            }
            return title to firmware
        }

        private fun pageTitle(page: String): String? {
            val lower = page.lowercase()
            val start = lower.indexOf("<title>").takeIf { it >= 0 }?.plus("<title>".length) ?: return null
            val end = lower.indexOf("</title>", start).takeIf { it >= 0 } ?: return null
            val title = page.substring(start, end).trim()
            // "SNR-CPE-AX2 - Обзор" on inner pages; the login page is the bare model.
            return title.split(" - ").first().trim()
        }
    }
}
