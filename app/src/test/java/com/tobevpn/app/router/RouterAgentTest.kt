package com.tobevpn.app.router

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.tobevpn.app.domain.model.Server
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class RouterAgentTest {

    @Test
    fun parsesStatusLikeTheDesktopApp() {
        val status = RouterAgent.parseStatus(
            "installed=1\nrunning=1\nautostart=0\ndown_slots=2 9 x\nmem_available_kb=41000\n" +
                "default_mode=direct\nname=\nhwid=abc\nclient_ip=192.168.1.107\nru_direct=0\n",
        )
        assertTrue(status.installed)
        assertTrue(status.running)
        assertFalse(status.autostart)
        assertEquals(listOf(2), status.downSlots)
        assertEquals(41000L, status.memoryAvailableKb)
        assertEquals("direct", status.defaultMode)
        assertNull(status.name)
        assertEquals("abc", status.hwid)
        assertEquals("192.168.1.107", status.clientIp)
        assertFalse(status.ruDirect)
        assertTrue(RouterAgent.parseStatus("installed=1\n").ruDirect)
    }

    @Test
    fun parsesDevicesAndSlots() {
        val text = "device=52:e1:c9:b5:1a:c8|192.168.1.107|Pixel-10-Pro|direct|Телефон|1|2\n" +
            "device=aa:bb:cc:dd:ee:ff|||default||0\nbroken line\nslot=2|nl2|Нидерланды 2\nslot=9|x|y\n"
        val devices = RouterAgent.parseDevices(text)
        assertEquals(2, devices.size)
        assertEquals(2, devices[0].slot)
        assertTrue(devices[0].online)
        assertEquals("Телефон", devices[0].name)
        assertEquals(0, devices[1].slot)
        assertNull(devices[1].ip)
        assertEquals(listOf(RouterSlot(2, "nl2", "Нидерланды 2")), RouterAgent.parseSlots(text))
    }

    @Test
    fun ruDirectTogglesCleanly() {
        val config = JSONObject(
            """{"inbounds":[{"tag":"socks0"},{"tag":"socks1"}],
               "routing":{"rules":[{"ip":["10.0.0.0/8"]},{"inboundTag":["socks0"]}]}}""",
        )
        val original = config.toString()
        RouterAgent.applyRuDirect(config, true)
        RouterAgent.applyRuDirect(config, true)
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        assertEquals(3, rules.length())
        assertEquals("direct", rules.getJSONObject(1).getString("outboundTag"))
        assertEquals(
            RouterAgent.RU_DIRECT_DOMAIN_GROUPS[0],
            rules.getJSONObject(1).getJSONArray("domain").getString(0),
        )
        assertTrue(config.getJSONArray("inbounds").getJSONObject(1).getJSONObject("sniffing").getBoolean("routeOnly"))
        RouterAgent.applyRuDirect(config, false)
        assertEquals(JSONObject(original).toString(), config.toString())
    }

    @Test
    fun xrayConfigMatchesTheDesktopShape() {
        val server = Server(
            id = "h:443:a.b", name = "NL", address = "h", port = 443,
            uuid = "11111111-1111-4111-8111-111111111111", publicKey = "k", shortId = "s", sni = "a.b",
        )
        val config = JSONObject(RouterAgent.xrayConfig(listOf(SlotServer(0, server), SlotServer(2, server)), ruDirect = true))
        val inbounds = config.getJSONArray("inbounds")
        assertEquals(10810, inbounds.getJSONObject(1).getInt("port"))
        assertEquals("proxy2", config.getJSONArray("outbounds").getJSONObject(1).getString("tag"))
        assertEquals("direct", config.getJSONArray("outbounds").getJSONObject(2).getString("tag"))
        assertEquals(server.uuid, config.getJSONObject("xudp").getString("baseKey"))
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        assertEquals(4, rules.length())
        assertEquals("proxy2", rules.getJSONObject(3).getString("outboundTag"))
    }

    @Test
    fun serverListKeepsPlainHosts() {
        assertEquals(
            "a.example\nb.example\n",
            RouterAgent.serverList(listOf(" b.example", "a.example", "b.example", "bad host", "x;rm", "")),
        )
    }

    private fun geositeEntry(code: String, domain: String): ByteArray {
        val item = byteArrayOf(0x08, 2, 0x12, domain.length.toByte()) + domain.toByteArray()
        val site = byteArrayOf(0x0a, code.length.toByte()) + code.toByteArray() +
            byteArrayOf(0x12, item.size.toByte()) + item
        return byteArrayOf(0x0a, site.size.toByte()) + site
    }

    @Test
    fun trimGeositeKeepsOnlyWantedGroups() {
        val ru = geositeEntry("CATEGORY-RU", "yandex.ru")
        val cn = geositeEntry("CN", "baidu.com")
        val gov = geositeEntry("CATEGORY-GOV-RU", "gosuslugi.ru")
        val data = ru + cn + gov
        val trimmed = RouterAgent.trimGeosite(
            ByteArrayInputStream(data),
            listOf("geosite:category-ru", "geosite:category-gov-ru"),
        )
        assertTrue((ru + gov).contentEquals(trimmed))
        assertTrue(runCatching { RouterAgent.trimGeosite(ByteArrayInputStream(data), listOf("geosite:x")) }.isFailure)
        val withMissing = RouterAgent.trimGeosite(
            ByteArrayInputStream(data),
            listOf("geosite:category-ru", "geosite:category-betting-ru"),
        )
        assertTrue((ru + byteArrayOf(0x0a, 21, 0x0a, 19) + "CATEGORY-BETTING-RU".toByteArray()).contentEquals(withMissing))
        assertTrue(runCatching { RouterAgent.trimGeosite(ByteArrayInputStream(data.copyOf(data.size - 1)), listOf("geosite:cn")) }.isFailure)
    }

    @Test
    fun trimsTheAppsRealList() {
        val file = File("src/main/assets/geosite.dat").takeIf { it.isFile } ?: return
        val trimmed = file.inputStream().use { RouterAgent.trimGeosite(it, RouterAgent.RU_DIRECT_DOMAIN_GROUPS) }
        assertTrue("${trimmed.size}", trimmed.size in 5_000 until 512 * 1024)
    }

    @Test
    fun ed25519KeyWithBouncyCastle() {
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
        val out = ByteArrayOutputStream()
        pair.writeOpenSSHv1PrivateKey(out, null, "none", 0)
        val loaded = KeyPair.load(JSch(), out.toByteArray(), null)
        assertTrue(pair.publicKeyBlob.contentEquals(loaded.publicKeyBlob))
        assertTrue(String(out.toByteArray()).contains("OPENSSH PRIVATE KEY"))
    }

    @Test
    fun plansSlotsLikeTheDesktopApp() {
        fun server(id: String) = Server(id = id, name = id, address = id, port = 443, uuid = "u")
        val servers = listOf(server("a"), server("b"), server("c"))
        val network = RouterNetwork(
            devices = listOf(
                RouterDevice("m1", null, null, "vpn", null, true, 0),
                RouterDevice("m2", null, null, "vpn", null, true, 1),
            ),
            slots = listOf(RouterSlot(0, "a", "a"), RouterSlot(1, "b", "b")),
        )
        val kept = RouterRepository.planSlots(servers, network, "m3", "b", limit = 4)
        assertEquals(RouterRepository.Companion.SlotPlan.Ready(null, 1), kept)
        val added = RouterRepository.planSlots(servers, network, "m3", "c", limit = 4)
            as RouterRepository.Companion.SlotPlan.Ready
        assertEquals(2, added.slot)
        assertEquals(listOf(0, 1, 2), added.list!!.map { it.slot })
        assertEquals(
            RouterRepository.Companion.SlotPlan.Full,
            RouterRepository.planSlots(servers, network, "m3", "c", limit = 2),
        )
    }

    @Test
    fun keepsSlotZeroForDefaultDevicesWhenTheDefaultIsVpn() {
        fun server(id: String) = Server(id = id, name = id, address = id, port = 443, uuid = "u")
        val servers = listOf(server("a"), server("b"))
        val network = RouterNetwork(
            devices = listOf(RouterDevice("m1", null, null, "default", null, true, 0)),
            slots = listOf(RouterSlot(0, "a", "a")),
        )
        // Default devices ride slot 0: a new server must not take it over.
        val plan = RouterRepository.planSlots(servers, network, "m2", "b", limit = 4, defaultVpn = true)
            as RouterRepository.Companion.SlotPlan.Ready
        assertEquals(1, plan.slot)
        assertEquals(listOf(0, 1), plan.list!!.map { it.slot })
        // With the default direct, slot 0 is free to reuse.
        val reuse = RouterRepository.planSlots(servers, network, "m2", "b", limit = 4, defaultVpn = false)
            as RouterRepository.Companion.SlotPlan.Ready
        assertEquals(0, reuse.slot)
    }

    @Test
    fun routerMarkMatchesTheAgent() {
        // Router shell: printf '%s' "befe0dc8-..." | sha256sum | cut -c1-16
        assertEquals("b5eda37d94f713a3", routerMark("BEFE0DC8-F668-5434-847A-1D108CE7D224"))
    }

    @Test
    fun matchesRouterModelsWithAVendorInFrontButNotOtherModels() {
        assertTrue(sameRouterModel("SNR-SNR-CPE-AX2", "SNR-CPE-AX2"))
        assertTrue(sameRouterModel("snr cpe ax2", "SNR-CPE-AX2"))
        assertFalse(sameRouterModel("SNR-CPE-AX23", "SNR-CPE-AX2"))
        assertFalse(sameRouterModel("AX", "AX"))
    }
}

class RouterInstallerTest {
    @Test
    fun routerIdMatchesTheDesktopApp() {
        // Python: uuid.uuid5(uuid.NAMESPACE_OID, "tobevpn-router:D4:AB:61:46:28:00"),
        // the same as Rust's Uuid::new_v5(&NAMESPACE_OID, ...).
        assertEquals(
            "befe0dc8-f668-5434-847a-1d108ce7d224",
            RouterInstaller.uuidV5(
                java.util.UUID.fromString("6ba7b812-9dad-11d1-80b4-00c04fd430c8"),
                "tobevpn-router:D4:AB:61:46:28:00",
            ).toString(),
        )
    }

    @Test
    fun comparesXrayVersions() {
        assertTrue(RouterInstaller.compareVersions(RouterInstaller.versionTuple("26.11.2"), RouterInstaller.versionTuple("26.10.31")) > 0)
        assertEquals(emptyList<Int>(), RouterInstaller.versionTuple("v26.1"))
        assertEquals(0, RouterInstaller.compareVersions(listOf(26, 10), listOf(26, 10, 0)))
    }
}
