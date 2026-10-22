package com.tobevpn.app.vpn

import com.tobevpn.app.domain.model.ServerProtocol
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Hysteria2Test {

    // The working 3x-ui link the feature was built against (credentials of a
    // test server; the shape is what matters).
    private val realLink = "hysteria2://21c638bfdf094cd3a535500240d5ee0f@meow.zchk.ru:443" +
        "?alpn=h3%2Ch2%2Chttp%2F1.1&ech=AGT%2BDQBgAAAgACAJghoNKFPwgiuwFEgHScjyuE5qlh4kkBbukv2p0el8Ag" +
        "AkAAEAAQABAAIAAQADAAIAAQACAAIAAgADAAMAAQADAAIAAwADABF3d3cucGludGVyZXN0LmNvbQAA" +
        "&fp=firefox&obfs=salamander&obfs-password=5xh017lfyq5kwx64&security=tls&sni=meow.zchk.ru" +
        "#%D1%85%D0%B8%D1%81%D1%82%D0%B5%D1%80%D0%B8%D1%8F2-alex"

    @Test
    fun parsesRealThreeXUiLink() {
        val server = Hysteria2UrlParser.parse(realLink)
        assertNotNull(server)
        server!!
        assertEquals(ServerProtocol.HYSTERIA2, server.protocol)
        assertEquals("meow.zchk.ru", server.address)
        assertEquals(443, server.port)
        assertEquals("21c638bfdf094cd3a535500240d5ee0f", server.uuid)
        assertEquals("meow.zchk.ru", server.sni)
        assertEquals("h3,h2,http/1.1", server.alpn)
        assertEquals("5xh017lfyq5kwx64", server.obfsPassword)
        assertTrue(server.echConfigList.startsWith("AGT+DQBg"))
        assertEquals("хистерия2-alex", server.name)
        assertTrue(server.isAvailable)
    }

    @Test
    fun parsesShortSchemeDefaultsAndPortHopping() {
        val short = Hysteria2UrlParser.parse("hy2://pass@example.com/?sni=cdn.example#Name")!!
        assertEquals(443, short.port)
        assertEquals("h3", short.alpn)
        assertEquals("", short.obfsPassword)

        val hopping = Hysteria2UrlParser.parse("hysteria2://u:p@example.com:443,20000-30000")!!
        assertEquals("u:p", hopping.uuid)
        assertEquals(443, hopping.port)
        assertEquals("443,20000-30000", hopping.portHopping)

        val mport = Hysteria2UrlParser.parse("hysteria2://p@example.com:8443?mport=10000-20000")!!
        assertEquals(8443, mport.port)
        assertEquals("10000-20000", mport.portHopping)
    }

    @Test
    fun pinIsNormalisedOrDropped() {
        val pin = "1C:DE:C0:7B:F4:1E:95:FB:09:C8:0B:45:78:A1:AB:90:53:45:76:D6:99:63:85:7D:EB:35:6B:BD:84:5C:C6:80"
        val ok = Hysteria2UrlParser.parse("hysteria2://p@h.example?pinSHA256=$pin")!!
        assertEquals("1cdec07bf41e95fb09c80b4578a1ab90534576d69963857deb356bbd845cc680", ok.pinSha256)
        val bad = Hysteria2UrlParser.parse("hysteria2://p@h.example?pinSHA256=zz")!!
        assertEquals("", bad.pinSha256)
    }

    @Test
    fun rejectsOtherSchemesAndBrokenLinks() {
        assertNull(Hysteria2UrlParser.parse("vless://id@host:443"))
        assertNull(Hysteria2UrlParser.parse("hysteria2://@host:443"))
        assertNull(Hysteria2UrlParser.parse("hysteria2://pass@"))
        assertNull(VlessUrlParser.parse(realLink))
    }

    @Test
    fun buildsXrayHysteriaOutbound() {
        val server = Hysteria2UrlParser.parse(realLink)!!
        val config = JSONObject(VpnConfig.buildConfigJson(server))
        val proxy = config.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("hysteria", proxy.getString("protocol"))
        assertEquals(2, proxy.getJSONObject("settings").getInt("version"))
        assertEquals("meow.zchk.ru", proxy.getJSONObject("settings").getString("address"))
        val stream = proxy.getJSONObject("streamSettings")
        assertEquals("hysteria", stream.getString("network"))
        val tls = stream.getJSONObject("tlsSettings")
        assertEquals("meow.zchk.ru", tls.getString("serverName"))
        assertEquals(3, tls.getJSONArray("alpn").length())
        assertTrue(tls.has("echConfigList"))
        assertFalse(tls.has("allowInsecure"))
        assertEquals(
            "21c638bfdf094cd3a535500240d5ee0f",
            stream.getJSONObject("hysteriaSettings").getString("auth"),
        )
        val mask = stream.getJSONObject("finalmask").getJSONArray("udp").getJSONObject(0)
        assertEquals("salamander", mask.getString("type"))
        assertFalse(proxy.has("mux"))

        // Dumped for an end-to-end check with a real Xray (see the commit).
        File("build/hy2-test-config.json").writeText(config.toString(2))
        File("build/hy2-test-delay-config.json").writeText(VpnConfig.buildOutboundDelayConfigJson(server))
    }

    @Test
    fun buildsHoppingAndPinnedVariants() {
        val hopping = Hysteria2UrlParser.parse(
            "hysteria2://p@example.com:443,20000-30000?obfs=salamander&obfs-password=x&sni=example.com",
        )!!
        val hopStream = JSONObject(VpnConfig.buildConfigJson(hopping))
            .getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        val masks = hopStream.getJSONObject("finalmask").getJSONArray("udp")
        assertEquals("udphop", masks.getJSONObject(0).getString("type"))
        assertEquals("443,20000-30000", masks.getJSONObject(0).getJSONObject("settings").getString("remotePorts"))
        assertEquals("salamander", masks.getJSONObject(1).getString("type"))
        File("build/hy2-test-hop-config.json").writeText(VpnConfig.buildConfigJson(hopping))

        val pinned = Hysteria2UrlParser.parse(
            "hysteria2://p@203.0.113.5:8443?pinSHA256=" + "ab".repeat(32),
        )!!
        val tls = JSONObject(VpnConfig.buildConfigJson(pinned))
            .getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
            .getJSONObject("tlsSettings")
        assertEquals("203.0.113.5", tls.getString("serverName"))
        assertEquals("ab".repeat(32), tls.getString("pinnedPeerCertSha256"))
        File("build/hy2-test-pin-config.json").writeText(VpnConfig.buildConfigJson(pinned))
    }

    @Test
    fun vlessConfigIsUnchanged() {
        val vless = VlessUrlParser.parse(
            "vless://00000000-0000-0000-0000-000000000001@example.com:443?security=reality&type=tcp" +
                "&sni=www.example.com&pbk=key&sid=ab#v",
        )!!
        assertEquals(ServerProtocol.VLESS, vless.protocol)
        val proxy = JSONObject(VpnConfig.buildConfigJson(vless)).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", proxy.getString("protocol"))
        assertTrue(proxy.getJSONObject("streamSettings").has("realitySettings"))
    }
}
