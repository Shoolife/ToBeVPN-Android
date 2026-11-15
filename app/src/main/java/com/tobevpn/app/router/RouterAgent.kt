package com.tobevpn.app.router

import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.vpn.VpnConfig
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The router side as the desktop app drives it: the agent script
 * /opt/tobevpn/tobevpn-router.sh, its answers and the Xray config for the
 * router. Kept byte-compatible with the desktop app, so a router set up from
 * either is managed by both.
 */
data class RouterStatus(
    val installed: Boolean = false,
    val running: Boolean = false,
    val autostart: Boolean = false,
    val xrayMemoryKb: Long? = null,
    val memoryAvailableKb: Long? = null,
    val tunnelRxBytes: Long = 0,
    val tunnelTxBytes: Long = 0,
    /** "vpn" or "direct": the mode of devices without their own choice. */
    val defaultMode: String = "vpn",
    /** This phone's address in the home network, as the router sees it. */
    val clientIp: String? = null,
    /** The core does not start: the home is direct, the router retries every minute. */
    val failed: Boolean = false,
    /** Slots whose server does not answer: their devices go direct for now. */
    val downSlots: List<Int> = emptyList(),
    /** Name the owner gave the router; null keeps the model. */
    val name: String? = null,
    /** The router's device id in the account. */
    val hwid: String? = null,
    /** Russian sites go around the VPN. */
    val ruDirect: Boolean = true,
)

data class RouterDevice(
    val mac: String,
    val ip: String?,
    /** Name the device reported over DHCP. */
    val hostname: String?,
    /** "vpn", "direct" or "default" (follows the router's default mode). */
    val mode: String,
    /** Name the user gave the device. */
    val name: String?,
    val online: Boolean,
    /** Server slot: 0 is the router's server. */
    val slot: Int,
)

data class RouterSlot(val slot: Int, val serverId: String, val serverName: String)

data class RouterNetwork(val devices: List<RouterDevice>, val slots: List<RouterSlot>) {
    /**
     * The slot a device really rides, as the router agent reads it: its own
     * when that slot has a server on the router, otherwise slot 0.
     */
    fun slotOf(device: RouterDevice): Int = if (slots.any { it.slot == device.slot }) device.slot else 0
}

/** A server in a slot (0-7); devices are sent to their server's slot. */
data class SlotServer(val slot: Int, val server: Server)

/** VPN traffic of the whole home network through the router in one hour. */
data class TrafficHour(
    /** Epoch seconds of the hour's start. */
    val hour: Long,
    val bytes: Long,
)

object RouterAgent {
    /** `traffic` output: one "<hour epoch> <bytes>" line per hour, as on PC. */
    fun parseTraffic(text: String): List<TrafficHour> = text.lineSequence().mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"))
        val hour = fields.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val bytes = fields.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        TrafficHour(hour, bytes)
    }.toList()

    const val SCRIPT = "/opt/tobevpn/tobevpn-router.sh"
    const val MAX_SLOT = 7
    const val MAX_SERVERS = MAX_SLOT + 1
    private const val SOCKS_PORT = 10808
    const val RU_DIRECT_FLAG = "/opt/tobevpn/ru_direct"
    const val RU_SITES_PATH = "/opt/tobevpn/geosite.dat"
    const val RU_SITES_DIGEST = "/opt/tobevpn/geosite.sha256"

    /** Memory one more server takes on the router, and what is always left free. */
    private const val SERVER_MEMORY_KB = 6 * 1024
    private const val ROUTER_RESERVE_KB = 28 * 1024

    private val PRIVATE_RANGES = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
        "127.0.0.0/8", "169.254.0.0/16", "100.64.0.0/10",
    )

    /** The app's Russian categories sent direct, as in its own routing. */
    val RU_DIRECT_DOMAIN_GROUPS = listOf(
        "geosite:category-bank-ru",
        "geosite:category-betting-ru",
        "geosite:category-ecommerce-ru",
        "geosite:category-entertainment-ru",
        "geosite:category-gov-ru",
        "geosite:category-media-ru",
        "geosite:category-medicine-ru",
        "geosite:category-retail-ru",
        "geosite:category-ru",
        "geosite:category-travel-ru",
        "geosite:ru-available-only-inside",
    )

    /**
     * How many different servers this router can hold at once: those on it
     * plus what its free memory still fits.
     */
    fun serverLimit(status: RouterStatus, onRouter: Int): Int {
        val free = status.memoryAvailableKb ?: return minOf(MAX_SERVERS, maxOf(onRouter, 4))
        val more = maxOf(0L, (free - ROUTER_RESERVE_KB) / SERVER_MEMORY_KB).toInt()
        return minOf(MAX_SERVERS, maxOf(1, onRouter + more))
    }

    // ------------------------------------------------------------ parsing ---

    fun keyValues(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line ->
            val at = line.indexOf('=')
            if (at < 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
        }
        .toMap()

    fun parseStatus(text: String): RouterStatus {
        val values = keyValues(text)
        fun flag(key: String) = values[key] == "1"
        fun number(key: String) = values[key]?.toLongOrNull()
        return RouterStatus(
            installed = flag("installed"),
            running = flag("running"),
            autostart = flag("autostart"),
            xrayMemoryKb = number("xray_rss_kb"),
            memoryAvailableKb = number("mem_available_kb"),
            tunnelRxBytes = number("tun_rx_bytes") ?: 0,
            tunnelTxBytes = number("tun_tx_bytes") ?: 0,
            defaultMode = values["default_mode"] ?: "vpn",
            clientIp = values["client_ip"]?.takeIf { it.isNotEmpty() },
            failed = values.containsKey("failed"),
            downSlots = values["down_slots"].orEmpty().split(' ')
                .mapNotNull { it.toIntOrNull() }
                .filter { it in 0..MAX_SLOT },
            name = values["name"]?.takeIf { it.isNotEmpty() },
            hwid = values["hwid"]?.takeIf { it.isNotEmpty() },
            ruDirect = values["ru_direct"] != "0",
        )
    }

    fun parseSlots(text: String): List<RouterSlot> = text.lineSequence()
        .mapNotNull { it.removePrefix("slot=").takeIf { rest -> rest != it } }
        .mapNotNull { line ->
            val fields = line.split('|', limit = 3)
            val slot = fields.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..MAX_SLOT } ?: return@mapNotNull null
            val id = fields.getOrNull(1) ?: return@mapNotNull null
            RouterSlot(slot, id, fields.getOrNull(2).orEmpty())
        }
        .toList()

    fun parseDevices(text: String): List<RouterDevice> = text.lineSequence()
        .mapNotNull { it.removePrefix("device=").takeIf { rest -> rest != it } }
        .mapNotNull { line ->
            val fields = line.split('|')
            if (fields.size < 6) return@mapNotNull null
            fun present(value: String) = value.takeIf { it.isNotEmpty() }
            RouterDevice(
                mac = fields[0],
                ip = present(fields[1]),
                hostname = present(fields[2]),
                mode = fields[3],
                name = present(fields[4]),
                online = fields[5] == "1",
                slot = fields.getOrNull(6)?.toIntOrNull()?.takeIf { it in 0..MAX_SLOT } ?: 0,
            )
        }
        .sortedWith(compareByDescending<RouterDevice> { it.online }.thenBy(nullsFirst()) { it.ip })
        .toList()

    // ------------------------------------------------------------ config ---

    /** One SOCKS input per slot, each sent to its own server. */
    fun xrayConfig(servers: List<SlotServer>, ruDirect: Boolean): String {
        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val rules = JSONArray().put(
            JSONObject()
                .put("type", "field")
                .put("ip", JSONArray(PRIVATE_RANGES))
                .put("outboundTag", "direct"),
        )
        for (item in servers) {
            inbounds.put(
                JSONObject()
                    .put("tag", "socks${item.slot}")
                    .put("listen", "127.0.0.1")
                    .put("port", SOCKS_PORT + item.slot)
                    .put("protocol", "socks")
                    .put("settings", JSONObject().put("auth", "noauth").put("udp", true)),
            )
            outbounds.put(VpnConfig.buildProxyOutbound(item.server, "proxy${item.slot}"))
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put("socks${item.slot}"))
                    .put("outboundTag", "proxy${item.slot}"),
            )
        }
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        val baseKey = servers.firstOrNull { it.slot == 0 }?.server?.uuid.orEmpty()
        val config = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
            .put("xudp", JSONObject().put("baseKey", baseKey))
        applyRuDirect(config, ruDirect)
        return config.toString(2)
    }

    /**
     * Turns the Russian sites bypass on or off in a router Xray config: the
     * site's name is read from each connection (only to pick the route) and
     * the app's Russian categories go direct, right after the home network
     * rule. The same as the desktop app's apply_ru_direct.
     */
    fun applyRuDirect(config: JSONObject, enabled: Boolean) {
        config.optJSONArray("inbounds")?.let { inbounds ->
            for (index in 0 until inbounds.length()) {
                val inbound = inbounds.optJSONObject(index) ?: continue
                if (enabled) {
                    inbound.put(
                        "sniffing",
                        JSONObject()
                            .put("enabled", true)
                            .put("destOverride", JSONArray(listOf("http", "tls", "quic")))
                            .put("routeOnly", true),
                    )
                } else {
                    inbound.remove("sniffing")
                }
            }
        }
        val rules = config.optJSONObject("routing")?.optJSONArray("rules") ?: return
        val ours = JSONArray(RU_DIRECT_DOMAIN_GROUPS).toString()
        val kept = JSONArray()
        for (index in 0 until rules.length()) {
            val rule = rules.opt(index)
            if ((rule as? JSONObject)?.optJSONArray("domain")?.toString() != ours) kept.put(rule)
        }
        val result = JSONArray()
        for (index in 0 until kept.length()) {
            if (enabled && index == 1) result.put(ruRule())
            result.put(kept.get(index))
        }
        if (enabled && kept.length() <= 1) result.put(ruRule())
        config.getJSONObject("routing").put("rules", result)
    }

    private fun ruRule() = JSONObject()
        .put("type", "field")
        .put("domain", JSONArray(RU_DIRECT_DOMAIN_GROUPS))
        .put("outboundTag", "direct")

    fun hevConfig(slot: Int): String =
        "tunnel:\n  name: tobe$slot\n  mtu: 1500\n  multi-queue: false\n  ipv4: 198.18.$slot.1\n" +
            "socks5:\n  port: ${SOCKS_PORT + slot}\n  address: 127.0.0.1\n  udp: udp\nmisc:\n  log-level: warn\n"

    /** The slot list the agent reads: slot, server id and name. */
    fun slotsFile(servers: List<SlotServer>): String = servers.joinToString("") { item ->
        "${item.slot}\t${clean(item.server.id)}\t${clean(item.server.name)}\n"
    }

    private fun clean(value: String) = value.filter { !it.isISOControl() && it != '|' }.take(80)

    /**
     * ToBeVPN server and service hosts, one per line; anything but a plain
     * host name or address is dropped, as the agent passes them to resolveip.
     */
    fun serverList(hosts: Collection<String>): String = hosts
        .map { it.trim() }
        .filter { host ->
            host.isNotEmpty() && host.length <= 253 &&
                host.all { it.isLetterOrDigit() && it.code < 128 || it in ".:-" }
        }
        .distinct()
        .sorted()
        .joinToString("") { "$it\n" }

    fun validMac(mac: String): Boolean {
        val parts = mac.split(':')
        return mac.length == 17 && parts.size == 6 &&
            parts.all { part -> part.length == 2 && part.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } }
    }

    /**
     * Writes the router's servers: the Russian sites list when the bypass
     * is on and the router has none yet (the phone's list is the smaller
     * one), xray.json, one tunnel config per slot, the slot list and the
     * addresses that go around the tunnels. Used by the install and by every
     * later server change, so both leave the same layout.
     */
    fun writeServerConfig(
        shell: RouterShell,
        servers: List<SlotServer>,
        sites: ByteArray?,
        bypassHosts: Collection<String>,
    ) {
        val ruDirect = shell.run("cat $RU_DIRECT_FLAG 2>/dev/null").first.trim() != "0"
        if (ruDirect && sites != null) writeRuSitesIfMissing(shell, sites)
        // The rule goes in whenever the router has a list (its own, e.g. the
        // desktop app's, even when this phone has none to give).
        val withSites = ruDirect && shell.run("[ -s $RU_SITES_PATH ]").second == 0
        shell.writeFile("/opt/tobevpn/xray.json", xrayConfig(servers, withSites).toByteArray(), "600")
        for (slot in 0..MAX_SLOT) {
            val path = if (slot == 0) "/opt/tobevpn/hev.yml" else "/opt/tobevpn/hev$slot.yml"
            if (servers.any { it.slot == slot }) {
                shell.writeFile(path, hevConfig(slot).toByteArray(), "600")
            } else {
                shell.runOk("rm -f '$path'")
            }
        }
        shell.writeFile("/opt/tobevpn/slots", slotsFile(servers).toByteArray(), "644")
        shell.writeFile("/opt/tobevpn/servers", serverList(bypassHosts).toByteArray(), "644")
    }

    /** The phone's sites list, only when the router has none (the desktop app carries the fuller one). */
    fun writeRuSitesIfMissing(shell: RouterShell, sites: ByteArray) {
        if (shell.run("[ -s $RU_SITES_PATH ]").second == 0) return
        shell.writeFile(RU_SITES_PATH, sites, "644")
        shell.writeFile(RU_SITES_DIGEST, "${sha256Hex(sites)}\n".toByteArray(), "644")
    }

    fun shellQuoteInner(value: String) = value.replace("'", "'\\''")

    fun cleanName(name: String, max: Int = 40) =
        name.filter { !it.isISOControl() && it !in "|\\\"`$" }.take(max).trim()

    // -------------------------------------------------------- ru sites ---

    /**
     * Keeps the entries of a geosite list (protobuf: repeated GeoSite = 1,
     * each starting with its code = 1) whose code is one of [groups], copied
     * byte for byte: about 40 KB instead of the app's whole list. Read as a
     * stream, so the 70 MB list is never held in memory.
     */
    fun trimGeosite(input: java.io.InputStream, groups: List<String>): ByteArray {
        val wanted = groups.map { it.removePrefix("geosite:").lowercase() }
        val found = BooleanArray(wanted.size)
        val out = java.io.ByteArrayOutputStream()
        val source = java.io.DataInputStream(java.io.BufferedInputStream(input, 64 * 1024))
        fun damaged(): Nothing = throw RouterException("geosite.dat is damaged")
        /** A varint and its raw bytes; null at the end of the stream. */
        fun varint(atStart: Boolean): Pair<Long, ByteArray>? {
            val raw = java.io.ByteArrayOutputStream()
            var value = 0L
            var shift = 0
            while (shift < 64) {
                val byte = source.read()
                if (byte < 0) if (atStart && raw.size() == 0) return null else damaged()
                raw.write(byte)
                value = value or ((byte and 0x7f).toLong() shl shift)
                if (byte < 0x80) return value to raw.toByteArray()
                shift += 7
            }
            damaged()
        }
        while (true) {
            val (key, keyRaw) = varint(atStart = true) ?: break
            val (lengthValue, lengthRaw) = varint(atStart = false) ?: damaged()
            if (lengthValue < 0 || lengthValue > Int.MAX_VALUE) damaged()
            val length = lengthValue.toInt()
            val head = ByteArray(minOf(length, 160))
            source.readFully(head)
            var index = -1
            if (key == 0x0aL && head.size >= 2 && head[0].toInt() == 0x0a) {
                val codeLength = head[1].toInt() and 0xff
                if (codeLength < 0x80 && 2 + codeLength <= head.size) {
                    index = wanted.indexOf(String(head, 2, codeLength).lowercase())
                }
            }
            if (index >= 0) {
                found[index] = true
                out.write(keyRaw)
                out.write(lengthRaw)
                out.write(head)
                val rest = ByteArray(length - head.size)
                source.readFully(rest)
                out.write(rest)
            } else {
                var skip = (length - head.size).toLong()
                while (skip > 0) {
                    val skipped = source.skip(skip)
                    if (skipped <= 0) {
                        if (source.read() < 0) damaged()
                        skip -= 1
                    } else {
                        skip -= skipped
                    }
                }
            }
        }
        // A category this list lacks (an older list may miss one) is added
        // empty: Xray refuses a rule naming a category it cannot find.
        wanted.forEachIndexed { index, code ->
            if (!found[index]) {
                val bytes = code.uppercase().toByteArray()
                val site = byteArrayOf(0x0a, bytes.size.toByte()) + bytes
                out.write(byteArrayOf(0x0a, site.size.toByte()) + site)
            }
        }
        if (found.none { it }) throw RouterException("geosite.dat has none of the Russian categories")
        return out.toByteArray()
    }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
