package com.tobevpn.app.vpn

import com.tobevpn.app.data.repository.BaseStationBypassProfileParser
import com.tobevpn.app.domain.model.ServerProtocol
import java.net.URLEncoder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalMaskTest {

    private val mask = """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","length":"100-200"}}]}"""
    private fun fm(json: String) = URLEncoder.encode(json, "UTF-8")

    private val vless = "vless://11111111-2222-3333-4444-555555555555@node.example:443" +
        "?security=reality&type=tcp&sni=sni.example&pbk=key&sid=ab&fp=chrome"

    @Test
    fun `vless keeps the fm object as compact json`() {
        val server = VlessUrlParser.parse("$vless&fm=${fm(mask)}#Node")!!
        assertEquals(JSONObject(mask).toString(), JSONObject(server.finalMask).toString())
    }

    @Test
    fun `broken or non-object fm is dropped`() {
        listOf("not-json", fm("[1,2]"), fm("{}"), fm("null")).forEach { value ->
            assertEquals("", VlessUrlParser.parse("$vless&fm=$value#Node")!!.finalMask)
        }
    }

    @Test
    fun `fm replaces streamSettings finalmask for vless`() {
        val server = VlessUrlParser.parse("$vless&fm=${fm(mask)}#Node")!!
        val stream = JSONObject(VpnConfig.buildConfigJson(server))
            .getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertEquals("fragment", stream.getJSONObject("finalmask").getJSONArray("tcp").getJSONObject(0).getString("type"))
        assertTrue(stream.has("realitySettings"))
    }

    @Test
    fun `no fm leaves the config without finalmask`() {
        val stream = JSONObject(VpnConfig.buildConfigJson(VlessUrlParser.parse("$vless#Node")!!))
            .getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertFalse(stream.has("finalmask"))
    }

    @Test
    fun `hysteria2 fm replaces the built udp masks`() {
        val udp = """{"udp":[{"type":"salamander","settings":{"password":"from-fm"}}]}"""
        val server = Hysteria2UrlParser.parse(
            "hysteria2://pass@h.example:443?obfs=salamander&obfs-password=from-link&fm=${fm(udp)}#H",
        )!!
        val stream = JSONObject(VpnConfig.buildConfigJson(server))
            .getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        val masks = stream.getJSONObject("finalmask").getJSONArray("udp")
        assertEquals(1, masks.length())
        assertEquals("from-fm", masks.getJSONObject(0).getJSONObject("settings").getString("password"))
    }

    @Test
    fun `fm changes the config identity`() {
        val plain = VlessUrlParser.parse("$vless#Node")!!
        val masked = VlessUrlParser.parse("$vless&fm=${fm(mask)}#Node")!!
        assertFalse(plain.hasSameVpnConfig(masked))
        assertNotEquals(plain.probeKey, masked.probeKey)
    }

    @Test
    fun `bypass tab accepts hysteria2 and keeps existing vless ids`() {
        val servers = BaseStationBypassProfileParser.parse(
            listOf(
                "$vless#Plain",
                "hysteria2://pass@h.example:443?sni=h.example#Hy2",
                "hy2://pass2@h2.example:8443?obfs=salamander&obfs-password=x#Hy2 short",
                "$vless&fm=${fm(mask)}#Masked",
            ).joinToString("\n"),
        )
        assertEquals(4, servers.size)
        assertEquals(2, servers.count { it.protocol == ServerProtocol.HYSTERIA2 })
        // Pinned from the formula before this change: a VLESS profile without
        // fm keeps its id, so a saved bypass selection survives the update.
        assertEquals("bs:3e449f64965b97521cff221627a6b46c", servers.first { it.name == "Plain" }.id)
        assertNotEquals(servers.first { it.name == "Plain" }.id, servers.first { it.name == "Masked" }.id)
    }
}
