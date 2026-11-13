package com.tobevpn.app.router

import android.content.Context
import com.tobevpn.app.BuildConfig
import com.tobevpn.app.domain.model.Server
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** The router this phone manages: address and SSH login. */
data class RouterLink(
    val host: String,
    val user: String,
    /** Device id of the router in the account, once its status was read. */
    val hwid: String? = null,
    /** Name the owner gave it, kept on the router. */
    val name: String? = null,
    /** Model from its login page. */
    val model: String? = null,
)

/**
 * The Router section's work on the router: pairing, status, home devices
 * and their servers, the switches. Every call opens its own SSH session
 * through the Wi-Fi network.
 */
@Singleton
class RouterRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ssh: RouterSsh,
    private val detector: RouterDetector,
) {
    private val prefs = context.getSharedPreferences("tobevpn_router", Context.MODE_PRIVATE)
    private val _link = MutableStateFlow(loadLink())
    val link: StateFlow<RouterLink?> = _link.asStateFlow()

    private val _router = MutableStateFlow<RouterInfo?>(null)
    /** The router of the current network, as last detected. */
    val router: StateFlow<RouterInfo?> = _router.asStateFlow()

    suspend fun detect(): RouterInfo = detector.detect().also { info ->
        _router.value = info
        if (info.gateway != null) {
            prefs.edit()
                .putString(
                    KEY_ROUTER,
                    JSONObject()
                        .put("gateway", info.gateway)
                        .put("model", info.model.orEmpty())
                        .put("firmware", info.firmware.orEmpty())
                        .toString(),
                )
                .apply()
        }
    }

    /**
     * What is known at once about this network's router, without asking it:
     * the last detection when the gateway is the same, else just the gateway.
     * The screen shows it straight away and detects again in the background.
     */
    fun knownRouterHere(): RouterInfo? {
        val gateway = detector.homeLan()?.gateway ?: return null
        _router.value?.takeIf { it.gateway == gateway }?.let { return it }
        val saved = runCatching { JSONObject(prefs.getString(KEY_ROUTER, null) ?: "") }.getOrNull()
        if (saved?.optString("gateway") == gateway) {
            return RouterInfo(
                gateway = gateway,
                model = saved.optString("model").takeIf { it.isNotEmpty() },
                firmware = saved.optString("firmware").takeIf { it.isNotEmpty() },
            )
        }
        return RouterInfo(gateway = gateway)
    }

    /** The linked router, when it is the router of the current network. */
    fun linkHere(): RouterLink? {
        val link = _link.value ?: return null
        val gateway = detector.homeLan()?.gateway ?: return null
        return link.takeIf { it.host == gateway && ssh.isPaired(it.host) }
    }

    private fun network() = detector.homeLan()?.network

    // --------------------------------------------------------------- link ---

    private fun loadLink(): RouterLink? = runCatching {
        val json = JSONObject(prefs.getString(KEY_LINK, null) ?: return null)
        RouterLink(
            host = json.getString("host"),
            user = json.getString("user"),
            hwid = json.optString("hwid").takeIf { it.isNotEmpty() },
            name = json.optString("name").takeIf { it.isNotEmpty() },
            model = json.optString("model").takeIf { it.isNotEmpty() },
        )
    }.getOrNull()

    private fun saveLink(link: RouterLink?) {
        _link.value = link
        prefs.edit().apply {
            if (link == null) {
                remove(KEY_LINK)
            } else {
                putString(
                    KEY_LINK,
                    JSONObject()
                        .put("host", link.host)
                        .put("user", link.user)
                        .put("hwid", link.hwid.orEmpty())
                        .put("name", link.name.orEmpty())
                        .put("model", link.model.orEmpty())
                        .toString(),
                )
            }
        }.apply()
    }

    /** Remembers what the router reported about itself. */
    private fun remember(status: RouterStatus) {
        val link = _link.value ?: return
        val next = link.copy(hwid = status.hwid ?: link.hwid, name = status.name)
        if (next != link) saveLink(next)
    }

    // ------------------------------------------------------------ pairing ---

    suspend fun pair(host: String, user: String, password: String) {
        ssh.pair(network(), host, user, password)
        saveLink(RouterLink(host = host, user = user, model = _router.value?.model))
    }

    /**
     * Removes ToBeVPN from the router, as the desktop app does: the agent
     * undoes its routing and deletes its files and the apps' keys, and only
     * the packages the install added (kmod-tun, curl) are removed. The
     * router's own settings stay. This phone then forgets the router.
     */
    suspend fun uninstall() {
        withRouter { shell ->
            shell.runOk(
                "added=\$(cat /opt/tobevpn/packages 2>/dev/null); ${RouterAgent.SCRIPT} uninstall; " +
                    "if [ -n \"\$added\" ]; then if command -v apk >/dev/null 2>&1; then apk del \$added; " +
                    "else opkg remove \$added; fi; fi >/dev/null 2>&1; " +
                    // An older agent removes only the computer's key; the
                    // phones' keys go too.
                    "sed -i '/ tobevpn-[a-z0-9-]*router/d' /etc/dropbear/authorized_keys ~/.ssh/authorized_keys 2>/dev/null; true",
            )
        }
        unlink()
    }

    /** Forgets the router on this phone (its key stays until uninstall). */
    fun unlink() {
        _link.value?.let { ssh.forget(it.host) }
        saveLink(null)
    }

    private suspend fun <T> withRouter(block: (RouterShell) -> T): T {
        val link = _link.value ?: throw RouterException("router is not paired")
        return ssh.session(network(), link.host, link.user, block)
    }

    // ------------------------------------------------------------- status ---

    suspend fun status(): RouterStatus = withRouter { shell ->
        val (output, _) = shell.run("[ -x ${RouterAgent.SCRIPT} ] && ${RouterAgent.SCRIPT} status")
        RouterAgent.parseStatus(output)
    }.also(::remember)

    /** The router's VPN traffic by hour; kept, so the screen shows it at once next time. */
    suspend fun traffic(): List<TrafficHour> = withRouter { shell ->
        RouterAgent.parseTraffic(shell.runOk("${RouterAgent.SCRIPT} traffic"))
    }.also { hours ->
        val cache = JSONObject()
            .put("hwid", _link.value?.hwid.orEmpty())
            .put("fetchedAt", System.currentTimeMillis())
            .put("hours", org.json.JSONArray().apply { hours.forEach { put("${it.hour} ${it.bytes}") } })
        prefs.edit().putString(KEY_TRAFFIC, cache.toString()).apply()
    }

    /** The traffic read last from this router and when, or null. */
    fun cachedTraffic(): Pair<Long, List<TrafficHour>>? {
        val cache = runCatching { JSONObject(prefs.getString(KEY_TRAFFIC, null) ?: return null) }.getOrNull() ?: return null
        val hwid = _link.value?.hwid
        val cachedHwid = cache.optString("hwid")
        if (hwid != null && cachedHwid.isNotEmpty() && cachedHwid != hwid) return null
        val array = cache.optJSONArray("hours") ?: return null
        val text = (0 until array.length()).joinToString("\n") { array.optString(it) }
        return cache.optLong("fetchedAt") to RouterAgent.parseTraffic(text)
    }

    /**
     * Names the router, as on PC: the name lives on the router itself, so
     * every app connected to it shows the same one. Empty brings the model back.
     */
    suspend fun setName(name: String): RouterStatus = withRouter { shell ->
        val clean = name.filter { !it.isISOControl() && it !in "|\\\"`$" }.take(40).trim()
        shell.runOk("${RouterAgent.SCRIPT} set-name '${RouterAgent.shellQuoteInner(clean)}'")
        RouterAgent.parseStatus(shell.run("${RouterAgent.SCRIPT} status").first)
    }.also(::remember)

    suspend fun devices(): RouterNetwork = withRouter { shell ->
        val output = shell.runOk("${RouterAgent.SCRIPT} devices")
        RouterNetwork(RouterAgent.parseDevices(output), RouterAgent.parseSlots(output))
    }

    /** start | stop | restart | autostart-on | autostart-off */
    suspend fun control(action: String): RouterStatus = withRouter { shell ->
        val command = when (action) {
            "start", "stop", "restart" -> "${RouterAgent.SCRIPT} $action"
            "autostart-on" -> "/etc/init.d/tobevpn enable"
            "autostart-off" -> "/etc/init.d/tobevpn disable"
            else -> throw RouterException("unknown router action: $action")
        }
        shell.runOk(command)
        RouterAgent.parseStatus(shell.run("${RouterAgent.SCRIPT} status").first)
    }.also(::remember)

    suspend fun setDefaultMode(mode: String): RouterStatus {
        require(mode == "vpn" || mode == "direct")
        return withRouter { shell ->
            shell.runOk("${RouterAgent.SCRIPT} set-default $mode")
            RouterAgent.parseStatus(shell.run("${RouterAgent.SCRIPT} status").first)
        }.also(::remember)
    }

    /** Sets one device to "vpn" (through [slot]), "direct" or back to "default". */
    suspend fun setDevice(mac: String, mode: String, slot: Int, name: String) {
        val cleanMac = mac.trim().lowercase()
        if (!RouterAgent.validMac(cleanMac) || mode !in setOf("vpn", "direct", "default") || slot !in 0..RouterAgent.MAX_SLOT) {
            throw RouterException("invalid device")
        }
        val cleanName = name.filter { !it.isISOControl() && it != '|' }.take(40).trim()
        withRouter { shell ->
            shell.runOk(
                "${RouterAgent.SCRIPT} set-device '$cleanMac' '$mode' '$slot' '${RouterAgent.shellQuoteInner(cleanName)}'",
            )
        }
    }

    /**
     * Drops a home device from Wi-Fi for a moment, so that it reconnects and
     * forgets the DNS answers it cached before its VPN changed.
     */
    suspend fun kick(mac: String): Boolean {
        val cleanMac = mac.trim().lowercase()
        if (!RouterAgent.validMac(cleanMac)) throw RouterException("invalid MAC address")
        return withRouter { shell ->
            RouterAgent.keyValues(shell.run("${RouterAgent.SCRIPT} kick '$cleanMac'").first)["kicked"] == "1"
        }
    }

    /**
     * TCP connect time to each server through the Wi-Fi network, not the
     * phone's VPN: the router sends ToBeVPN servers direct as well, so this
     * is the home's own latency to them, as the desktop app measures it.
     * Hysteria2 runs over UDP and gets no TCP ping.
     */
    suspend fun pingServers(servers: List<Server>): Map<String, Long> = kotlinx.coroutines.coroutineScope {
        val network = network()
        servers
            .filter { it.protocol != com.tobevpn.app.domain.model.ServerProtocol.HYSTERIA2 }
            .map { server ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    server.id to runCatching {
                        val socket = network?.socketFactory?.createSocket() ?: java.net.Socket()
                        socket.use {
                            val started = System.nanoTime()
                            it.connect(java.net.InetSocketAddress(server.address, server.port), PING_TIMEOUT_MS)
                            (System.nanoTime() - started) / 1_000_000
                        }
                    }.getOrDefault(-1L)
                }
            }
            .awaitAll()
            .toMap()
    }

    // ------------------------------------------------------------ servers ---

    /**
     * Writes the router's servers (slot 0 is required), one tunnel config
     * per slot and the slot list, and restarts the tunnels.
     */
    suspend fun setServers(servers: List<SlotServer>, serverHosts: Collection<String>): RouterStatus {
        val slots = servers.map { it.slot }
        if (0 !in slots || slots.toSet().size != slots.size || slots.any { it !in 0..RouterAgent.MAX_SLOT }) {
            throw RouterException("invalid server slots")
        }
        val sites = ruSites()
        return withRouter { shell ->
            shell.writeFile("/opt/tobevpn/servers", RouterAgent.serverList(serverHosts + serviceHosts()).toByteArray(), "644")
            writeServers(shell, servers, sites)
            shell.runOk("${RouterAgent.SCRIPT} restart")
            RouterAgent.parseStatus(shell.run("${RouterAgent.SCRIPT} status").first)
        }.also(::remember)
    }

    private fun writeServers(shell: RouterShell, servers: List<SlotServer>, sites: ByteArray?) {
        val ruDirect = shell.run("cat ${RouterAgent.RU_DIRECT_FLAG} 2>/dev/null").first.trim() != "0"
        val withSites = ruDirect && sites != null
        if (withSites) writeRuSites(shell, sites!!)
        shell.writeFile("/opt/tobevpn/xray.json", RouterAgent.xrayConfig(servers, withSites).toByteArray(), "600")
        for (slot in 0..RouterAgent.MAX_SLOT) {
            val path = if (slot == 0) "/opt/tobevpn/hev.yml" else "/opt/tobevpn/hev$slot.yml"
            if (servers.any { it.slot == slot }) {
                shell.writeFile(path, RouterAgent.hevConfig(slot).toByteArray(), "600")
            } else {
                shell.runOk("rm -f '$path'")
            }
        }
        shell.writeFile("/opt/tobevpn/slots", RouterAgent.slotsFile(servers).toByteArray(), "644")
    }

    /** Turns the Russian sites bypass on or off in the config on the router and restarts. */
    suspend fun setRuDirect(enabled: Boolean): RouterStatus {
        val sites = if (enabled) ruSites() ?: throw RouterException("geosite.dat is missing") else null
        return withRouter { shell ->
            if (sites != null) writeRuSites(shell, sites)
            val config = JSONObject(shell.run("cat /opt/tobevpn/xray.json 2>/dev/null").first)
            RouterAgent.applyRuDirect(config, enabled)
            shell.writeFile("/opt/tobevpn/xray.json", config.toString(2).toByteArray(), "600")
            shell.writeFile(RouterAgent.RU_DIRECT_FLAG, if (enabled) "1\n".toByteArray() else "0\n".toByteArray(), "644")
            shell.runOk("${RouterAgent.SCRIPT} restart")
            RouterAgent.parseStatus(shell.run("${RouterAgent.SCRIPT} status").first)
        }.also(::remember)
    }

    /**
     * Puts the phone's list on the router only when it has none: the desktop
     * app carries the fuller list (its "Update" brings it), and the phone's
     * smaller one must not replace it.
     */
    private fun writeRuSites(shell: RouterShell, sites: ByteArray) {
        if (shell.run("[ -s ${RouterAgent.RU_SITES_PATH} ]").second == 0) return
        val digest = RouterAgent.sha256Hex(sites)
        shell.writeFile(RouterAgent.RU_SITES_PATH, sites, "644")
        shell.writeFile(RouterAgent.RU_SITES_DIGEST, "$digest\n".toByteArray(), "644")
    }

    @Volatile
    private var ruSitesCache: ByteArray? = null

    /** The app's geosite.dat cut down to its Russian categories, once per process. */
    internal fun ruSites(): ByteArray? = ruSitesCache ?: runCatching {
        context.assets.open("geosite.dat").use { RouterAgent.trimGeosite(it, RouterAgent.RU_DIRECT_DOMAIN_GROUPS) }
    }.getOrNull()?.also { ruSitesCache = it }

    /**
     * The app's own services (subscription, bot API). The router sends them
     * direct too, so the app behind the router reaches them as without it.
     */
    private fun serviceHosts(): List<String> = listOf(
        BuildConfig.SUBSCRIPTION_BASE_URL,
        BuildConfig.BOT_API_BASE_URL,
    ).mapNotNull { url ->
        runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
    } + listOf(BuildConfig.FALLBACK_BOT_DOMAIN, BuildConfig.FALLBACK_SUBS_DOMAIN).filter { it.isNotBlank() }

    companion object {
        private const val KEY_LINK = "link"
        private const val PING_TIMEOUT_MS = 2_000
        private const val KEY_ROUTER = "router_here"
        private const val KEY_TRAFFIC = "traffic"

        /** Slots that devices other than [exceptMac] go through. */
        fun usedSlots(network: RouterNetwork, exceptMac: String?): Set<Int> =
            network.devices.filter { it.mac != exceptMac && it.mode == "vpn" }.map { it.slot }.toSet()

        sealed interface SlotPlan {
            data class Ready(val list: List<SlotServer>?, val slot: Int) : SlotPlan
            data object Full : SlotPlan
            data object Missing : SlotPlan
        }

        /**
         * Where a device's server goes. A server already on the router keeps
         * its slot (list null: nothing to rewrite); otherwise it takes a free
         * slot and list is the router's new server list. As on PC.
         */
        fun planSlots(
            servers: List<Server>,
            network: RouterNetwork,
            exceptMac: String,
            serverId: String,
            limit: Int,
        ): SlotPlan {
            network.slots.firstOrNull { it.serverId == serverId }?.let { return SlotPlan.Ready(null, it.slot) }
            val server = servers.firstOrNull { it.id == serverId } ?: return SlotPlan.Missing
            val used = usedSlots(network, exceptMac)
            val list = network.slots
                .filter { it.slot in used }
                .mapNotNull { item -> servers.firstOrNull { it.id == item.serverId }?.let { SlotServer(item.slot, it) } }
                .toMutableList()
            if (list.size >= limit) return SlotPlan.Full
            val taken = list.map { it.slot }.toSet()
            val free = (0 until RouterAgent.MAX_SERVERS).firstOrNull { it !in taken } ?: return SlotPlan.Full
            list += SlotServer(free, server)
            return SlotPlan.Ready(list.sortedBy { it.slot }, free)
        }

        fun canAddServer(servers: List<Server>, network: RouterNetwork, exceptMac: String, limit: Int): Boolean {
            val used = usedSlots(network, exceptMac)
            return network.slots.count { item -> item.slot in used && servers.any { it.id == item.serverId } } < limit
        }
    }
}
