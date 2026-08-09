package com.tobevpn.app.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.tobevpn.app.domain.model.Server
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnConfigInstrumentedTest {

    @Test
    fun xrayNativeLibraryLoadsForCurrentAbi() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        XRayCore.init(context)
        val version = XRayCore.getVersion()

        assertTrue(version.isNotBlank())
        assertNotEquals("unknown", version)
    }

    @Test
    fun xhttpRealityConfigContainsRequiredTransportAndSecurityFields() {
        val root = config(
            server(
                network = "xhttp",
                security = "reality",
                path = "/api",
                mode = "stream-up",
            ),
        )
        val outbound = proxyOutbound(root)
        val stream = outbound.getJSONObject("streamSettings")

        assertEquals("xhttp", stream.getString("network"))
        assertEquals("reality", stream.getString("security"))
        assertEquals("/api", stream.getJSONObject("xhttpSettings").getString("path"))
        assertEquals(
            "stream-up",
            stream.getJSONObject("xhttpSettings").getString("mode"),
        )
        assertEquals(
            "public-key",
            stream.getJSONObject("realitySettings").getString("publicKey"),
        )
        assertFalse(outbound.has("mux"))
    }

    @Test
    fun websocketTlsConfigContainsWebsocketSettingsAndDisabledMux() {
        val outbound = proxyOutbound(
            config(server(network = "ws", security = "tls", path = "/socket")),
        )
        val stream = outbound.getJSONObject("streamSettings")

        assertEquals("ws", stream.getString("network"))
        assertEquals("/socket", stream.getJSONObject("wsSettings").getString("path"))
        assertEquals("front.example", stream.getJSONObject("tlsSettings").getString("serverName"))
        assertFalse(outbound.getJSONObject("mux").getBoolean("enabled"))
    }

    @Test
    fun tcpConfigUsesPlainHeaderAndKeepsFullTunnelInbounds() {
        val root = config(server(network = "tcp", security = "none"))
        val outbound = proxyOutbound(root)
        val stream = outbound.getJSONObject("streamSettings")
        val inbounds = root.getJSONArray("inbounds")

        assertEquals(
            "none",
            stream.getJSONObject("tcpSettings").getJSONObject("header").getString("type"),
        )
        assertFalse(stream.has("tlsSettings"))
        assertFalse(stream.has("realitySettings"))
        assertEquals("socks", inbounds.getJSONObject(0).getString("protocol"))
        assertEquals(VpnConfig.LOCAL_SOCKS_PORT, inbounds.getJSONObject(0).getInt("port"))
        assertEquals("tun", inbounds.getJSONObject(1).getString("protocol"))
        assertEquals(4, root.getJSONObject("dns").getJSONArray("servers").length())
        assertTrue(root.getJSONObject("routing").getJSONArray("rules").length() == 0)
    }

    private fun config(server: Server): JSONObject = JSONObject(VpnConfig.buildConfigJson(server))

    private fun proxyOutbound(root: JSONObject): JSONObject =
        root.getJSONArray("outbounds").getJSONObject(0)

    private fun server(
        network: String,
        security: String,
        path: String = "",
        mode: String = "",
    ) = Server(
        id = "server-id",
        name = "Test server",
        address = "node.example",
        port = 443,
        uuid = "550e8400-e29b-41d4-a716-446655440000",
        flow = "xtls-rprx-vision",
        security = security,
        sni = "front.example",
        fingerprint = "chrome",
        publicKey = "public-key",
        shortId = "abcd",
        network = network,
        path = path,
        mode = mode,
        spx = "/",
        country = "NL",
    )
}
