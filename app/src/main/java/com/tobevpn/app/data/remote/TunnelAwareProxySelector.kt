package com.tobevpn.app.data.remote

import com.tobevpn.app.BuildConfig
import com.tobevpn.app.vpn.XRayCore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Sends app API traffic through XRay after a tunnel is running while leaving
 * fallback endpoints on the underlying network. The ToBeVPN process itself is
 * intentionally excluded from the Android TUN to avoid recursively tunnelling
 * XRay's transport connection; using the local SOCKS listener routes only these
 * HTTP calls through the established connection.
 */
class TunnelAwareProxySelector : ProxySelector() {
    private val directFallbackEndpoints = listOf(
        BuildConfig.FALLBACK_BOT_DOMAIN,
        BuildConfig.FALLBACK_SUBS_DOMAIN,
    ).mapNotNull { configuredUrl ->
        configuredUrl
            .trim()
            .let { value ->
                when {
                    value.isBlank() -> null
                    value.startsWith("https://") || value.startsWith("http://") -> value
                    else -> "https://$value"
                }
            }
            ?.toHttpUrlOrNull()
            ?.let { endpoint ->
                DirectEndpoint(
                    host = endpoint.host,
                    port = endpoint.port,
                    path = endpoint.encodedPath,
                )
            }
    }.toSet()

    override fun select(uri: URI?): List<Proxy> {
        if (uri == null || isDirectFallback(uri) || !XRayCore.isRunning) {
            return DIRECT_ROUTE
        }
        return TUNNEL_ROUTE
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit

    private fun isDirectFallback(uri: URI): Boolean {
        val port = if (uri.port >= 0) uri.port else if (uri.scheme == "http") 80 else 443
        return DirectEndpoint(uri.host, port, uri.rawPath.orEmpty()) in directFallbackEndpoints
    }

    private data class DirectEndpoint(val host: String, val port: Int, val path: String)

    private companion object {
        val DIRECT_ROUTE = listOf(Proxy.NO_PROXY)
        val TUNNEL_ROUTE = listOf(
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808)),
        )
    }
}
